import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import lombok.extern.slf4j.Slf4j;

/**
 * 위성 OBC 시뮬레이터 서버 [Baseline UDP 비교군 + 실험 시작 동기화 버전]
 * 목적:
 * - ARQ 미적용 상태의 순수 UDP 제어 성능을 측정하기 위한 비교군
 * - 실험 반복성을 위해 첫 Ping(또는 최초 유효 TC) 수신 전까지 시뮬레이션 시간을 정지
 * 특징:
 * - Ping(0x0F) 허용
 * - 30% 무응답 fault injection 포함
 * - LOS/AOS + PB dump 유지
 * - RT/PB TM payload 7바이트 유지
 * - 재전송 없음
 * - 중복 방어 없음
 */
@Slf4j
public class SatelliteOBCBaseline {

	private static volatile SatelliteState currentState = SatelliteState.BOOT;

	private static volatile InetAddress lastClientAddress = null;
	private static volatile int lastClientPort = 0;
	private static short tmSeq = 1; // TM 전용 sequence

	private static volatile short battery = 100;      // 100%
	private static volatile short temperature = 15;   // 15도

	private static final Queue<byte[]> flashMemory = new ConcurrentLinkedQueue<>();
	private static final int MAX_MEMORY_CAPACITY = 100;

	// 궤도 시뮬레이션 전역 변수 & 상수
	private static final double ORBIT_SPEED_PER_SEC = 6.0;
	private static final double GS_CENTER_ANGLE = 180.0;
	private static final double VISIBILITY_HALF_ANGLE = 60.0;

	// 현재 궤도 각도
	private static volatile double currentOrbitAngle = 175.0;

	// 초기 가시권 상태
	private static volatile boolean wasInCoverage = calculateInitialCoverage(currentOrbitAngle);

	// 궤도 기반 통신 단절 추적 변수
	private static volatile boolean isCommunicationLost = !wasInCoverage;

	// 실험 시작 동기화 플래그
	private static volatile boolean simulationStarted = false;

	private static boolean calculateInitialCoverage(double orbitAngle) {
		return angularDifference(orbitAngle, GS_CENTER_ANGLE) <= VISIBILITY_HALF_ANGLE;
	}

	private static double angularDifference(double a, double b) {
		double diff = Math.abs(a - b) % 360.0;
		return diff > 180.0 ? 360.0 - diff : diff;
	}

	/**
	 * ACK / NAK 응답 패킷 전송
	 */
	private static void sendResponse(
		DatagramSocket socket,
		InetAddress clientAddress,
		int clientPort,
		short seq,
		boolean isAck
	) {
		try {
			byte[] header = new byte[8];
			ByteBuffer buffer = ByteBuffer.wrap(header);
			buffer.order(ByteOrder.BIG_ENDIAN);

			buffer.putShort((short) 0xCAFE);                // Magic
			buffer.put((byte) 0x01);                        // Ver
			buffer.put((byte) (isAck ? 0x00 : 0xFF));      // Type
			buffer.putShort(seq);                           // Seq
			buffer.putShort((short) 0);                     // Payload Length

			int crc = Crc16Utils.calculateCrc16Xmodem(header, 8);

			ByteBuffer packetBuffer = ByteBuffer.allocate(10);
			packetBuffer.order(ByteOrder.BIG_ENDIAN);
			packetBuffer.put(header);
			packetBuffer.putShort((short) crc);

			byte[] sendData = packetBuffer.array();
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, clientAddress, clientPort);
			socket.send(sendPacket);

			log.info("🚀 지상국으로 [{}] 패킷 전송 완료 (Seq: {})", isAck ? "ACK" : "NAK", seq);
		} catch (Exception e) {
			log.error("응답 패킷 전송 중 오류 발생: ", e);
		}
	}

	/**
	 * TM 패킷 생성
	 * payload = battery(2) + temperature(2) + mode(1) + orbitAngle(2) = 7 bytes
	 */
	private static byte[] buildTelemetryPacket(boolean isRealTime) {
		byte[] header = new byte[8];
		ByteBuffer buffer = ByteBuffer.wrap(header);
		buffer.order(ByteOrder.BIG_ENDIAN);

		buffer.putShort((short) 0xCAFE);
		buffer.put((byte) 0x01);
		buffer.put((byte) (isRealTime ? 0x05 : 0x06)); // RT=0x05, PB=0x06
		buffer.putShort(tmSeq++);
		buffer.putShort((short) 7);

		ByteBuffer packetBuffer = ByteBuffer.allocate(17);
		packetBuffer.order(ByteOrder.BIG_ENDIAN);
		packetBuffer.put(header);
		packetBuffer.putShort(battery);
		packetBuffer.putShort(temperature);

		byte modeByte = switch (currentState) {
			case BOOT -> 0x40;
			case SAFE -> 0x10;
			case EMERGENCY -> 0x30;
			case NOMINAL -> 0x20;
		};
		packetBuffer.put(modeByte);

		short scaledAngle = (short) Math.round(currentOrbitAngle * 10.0);
		packetBuffer.putShort(scaledAngle);

		int crc = Crc16Utils.calculateCrc16Xmodem(packetBuffer.array(), 15);
		packetBuffer.putShort((short) crc);

		return packetBuffer.array();
	}

	public static void main(String[] args) {
		int port = 9000;

		try (DatagramSocket socket = new DatagramSocket(port)) {
			log.info("위성 시스템 시작 - 지상국 명령 대기 중... (Port: {})", port);
			log.info("초기 위성 상태 고정 완료: orbit={}°, state={}, battery={}%, temp={}°C",
				currentOrbitAngle, currentState, battery, temperature);
			log.info("첫 Ping(또는 최초 유효 TC) 수신 전까지 시뮬레이션 시간은 정지합니다.");

			Thread tmThread = getTmThread(socket);
			tmThread.start();

			byte[] receiveBuffer = new byte[1024];

			while (true) {
				DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
				socket.receive(packet);

				if (packet.getLength() < 10) {
					log.warn("🛡️ 비정상적으로 짧은 패킷 수신 무시 (len: {})", packet.getLength());
					continue;
				}

				// Ping seed가 LOS 중에 들어와도 주소는 학습해야 하므로 먼저 업데이트
				lastClientAddress = packet.getAddress();
				lastClientPort = packet.getPort();

				ByteBuffer buffer = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
				buffer.order(ByteOrder.BIG_ENDIAN);

				short magic = buffer.getShort();
				if (magic != (short) 0xCAFE) {
					log.warn("잘못된 Magic Number 수신됨: 0x{}", Integer.toHexString(magic & 0xFFFF));
					continue;
				}

				byte[] receivedBytes = Arrays.copyOfRange(
					packet.getData(),
					packet.getOffset(),
					packet.getOffset() + packet.getLength()
				);
				int calculatedCrc = Crc16Utils.calculateCrc16Xmodem(receivedBytes, 8);

				byte ver = buffer.get();
				byte type = buffer.get();
				short seq = buffer.getShort();
				short len = buffer.getShort();

				int receivedCrc = buffer.getShort() & 0xFFFF;

				if (receivedCrc != calculatedCrc) {
					log.error("CRC 검증 실패! (수신: 0x{}, 계산: 0x{})",
						String.format("%04X", receivedCrc),
						String.format("%04X", calculatedCrc));
					sendResponse(socket, lastClientAddress, lastClientPort, seq, false);
					continue;
				}

				if (ver != (byte) 0x01) {
					log.warn("🛡️ 지원하지 않는 프로토콜 버전 수신 거부 (Ver: 0x{})",
						String.format("%02X", ver & 0xFF));
					sendResponse(socket, lastClientAddress, lastClientPort, seq, false);
					continue;
				}

				if (len != 0) {
					log.warn("🛡️ 비정상 TC Payload Length 수신 거부 (Len: {})", len);
					sendResponse(socket, lastClientAddress, lastClientPort, seq, false);
					continue;
				}

				// 유효한 TC 타입만 허용: Ping / SAFE / NOMINAL
				if (type != (byte) 0x0F && type != (byte) 0x10 && type != (byte) 0x20) {
					log.warn("👽 알 수 없는 TC Type 수신 거부 (Type: 0x{})", String.format("%02X", type & 0xFF));
					sendResponse(socket, lastClientAddress, lastClientPort, seq, false);
					continue;
				}

				// 첫 유효 TC 수신 시 시뮬레이션 시작
				if (!simulationStarted) {
					simulationStarted = true;
					wasInCoverage = calculateInitialCoverage(currentOrbitAngle);
					isCommunicationLost = !wasInCoverage;

					log.info("🌱 [Simulation Start] 최초 유효 TC 수신(Seq:{}, Type:0x{})으로 시뮬레이션을 시작합니다.",
						seq, String.format("%02X", type & 0xFF));
					log.info("초기 시작 조건: orbit={}°, inCoverage={}, state={}, battery={}%, temp={}°C",
						currentOrbitAngle, wasInCoverage, currentState, battery, temperature);
				}

				// LOS 상태에서는 명령 처리 불가
				// 단, 주소 학습/시뮬레이션 시작은 이미 위에서 끝났음
				if (isCommunicationLost) {
					log.warn("🚫 [LOS 상태] 궤도 이탈로 인해 수신된 명령(Seq:{})을 처리할 수 없습니다.", seq);
					continue;
				}

				// [비교 실험용] 30% 확률 의도적 무응답
				if (Math.random() < 0.30) {
					log.warn("🌌 [Uplink Loss] 무선 노이즈로 인해 TC 패킷(Seq:{})이 유실되었습니다.", seq);
					continue;
				}

				// Baseline: 중복 방어 없음
				if (type == (byte) 0x0F) {
					log.info("📡 [TC] 지상국 연결 확인 (Ping) 수신. FSM 상태 유지.");
				} else if (type == (byte) 0x10) {
					if (currentState != SatelliteState.SAFE) {
						currentState = SatelliteState.SAFE;
						log.info("📡 [TC] 지상국 명령: SAFE 모드 전환.");
					}
				} else if (type == (byte) 0x20) {
					if (currentState != SatelliteState.NOMINAL) {
						currentState = SatelliteState.NOMINAL;
						log.info("📡 [TC] 지상국 명령: NOMINAL 모드 전환.");
					}
				}

				log.info("현재 위성 상태: [{}]", currentState);
				sendResponse(socket, lastClientAddress, lastClientPort, seq, true);
			}

		} catch (Exception e) {
			log.error("위성 메인 루프에서 예외 발생: ", e);
		}
	}

	private static Thread getTmThread(DatagramSocket socket) {
		Thread tmThread = new Thread(() -> {
			int bootTimer = 0;

			while (true) {
				try {
					Thread.sleep(1000);

					// 실험 시작 동기화:
					// 첫 Ping(또는 최초 유효 TC) 수신 전까지는 아무것도 진행하지 않음
					if (!simulationStarted) {
						continue;
					}

					// 1) 궤도 갱신
					currentOrbitAngle += ORBIT_SPEED_PER_SEC;
					if (currentOrbitAngle >= 360.0) {
						currentOrbitAngle -= 360.0;
					}

					// 2) 가시권 판정
					boolean isInCoverage =
						angularDifference(currentOrbitAngle, GS_CENTER_ANGLE) <= VISIBILITY_HALF_ANGLE;

					if (isInCoverage && !wasInCoverage) {
						log.info("📡 [AOS 진입] 위성이 지상국 가시권에 진입했습니다. (현재: {}도)",
							String.format("%.1f", currentOrbitAngle));
						isCommunicationLost = false;
					} else if (!isInCoverage && wasInCoverage) {
						log.warn("💥 [LOS 진입] 위성이 지상국 가시권을 벗어났습니다. 통신이 두절됩니다. (현재: {}도)",
							String.format("%.1f", currentOrbitAngle));
						isCommunicationLost = true;
					}
					wasInCoverage = isInCoverage;

					// 3) FSM 상태 변경
					switch (currentState) {
						case BOOT:
							bootTimer++;
							if (bootTimer >= 3) {
								log.info("💻 [FSM] 시스템 부팅 완료. NOMINAL 모드로 자율 전환.");
								currentState = SatelliteState.NOMINAL;
							}
							break;

						case NOMINAL:
							battery = (short) Math.max(0, battery - 4);
							temperature = (short) Math.min(80, temperature + 4);

							if (temperature >= 60 || battery <= 5) {
								log.error("🔥/🪫 [FSM] 치명적 위기 감지 (온도:{}도, 배터리:{}%)! 시스템 보호를 위해 EMERGENCY 강제 전환",
									temperature, battery);
								currentState = SatelliteState.EMERGENCY;
							} else if (battery <= 20) {
								log.warn("⚠️ [FSM] 배터리 저전압 감지 ({}%)! 태양광 충전을 위해 자율 SAFE 모드 전환", battery);
								currentState = SatelliteState.SAFE;
							}
							break;

						case EMERGENCY:
							battery = (short) Math.min(100, battery + 1);
							temperature = (short) Math.max(-10, temperature - 5);
							break;

						case SAFE:
							battery = (short) Math.min(100, battery + 5);
							temperature = (short) Math.max(-10, temperature - 3);

							if (battery <= 5) {
								log.error("🪫 [FSM] SAFE 모드 중 배터리 치명적 고갈 ({}%)! EMERGENCY 강제 전환", battery);
								currentState = SatelliteState.EMERGENCY;
							}
							break;
					}

					// 4) TM 전송 / PB 저장 및 dump
					if (lastClientAddress != null) {
						if (isCommunicationLost) {
							byte[] pbTmData = buildTelemetryPacket(false);

							if (flashMemory.size() < MAX_MEMORY_CAPACITY) {
								flashMemory.offer(pbTmData);
								log.info("💾 [로깅 중] 전송 불가(LOS). 플래시 메모리에 저장합니다. (저장된 개수: {}, 궤도: {}도)",
									flashMemory.size(), String.format("%.1f", currentOrbitAngle));
							} else {
								flashMemory.poll();
								flashMemory.offer(pbTmData);
							}
						} else {
							while (!flashMemory.isEmpty()) {
								byte[] dumpData = flashMemory.poll();
								DatagramPacket dumpPacket =
									new DatagramPacket(dumpData, dumpData.length, lastClientAddress, lastClientPort);
								socket.send(dumpPacket);
								log.info("📥 [DUMP] 저장된 과거 TM 데이터를 전송합니다. (남은 데이터: {})", flashMemory.size());
								Thread.sleep(50);
							}

							byte[] rtTmData = buildTelemetryPacket(true);
							DatagramPacket tmPacket =
								new DatagramPacket(rtTmData, rtTmData.length, lastClientAddress, lastClientPort);
							socket.send(tmPacket);
						}
					}

				} catch (InterruptedException e) {
					break;
				} catch (Exception e) {
					log.error("TM 스레드 오류", e);
				}
			}
		});

		tmThread.setDaemon(true);
		return tmThread;
	}
}