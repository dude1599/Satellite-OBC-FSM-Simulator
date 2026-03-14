import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import lombok.extern.slf4j.Slf4j;

/**
 * 우주 위성 온보드 컴퓨터(OBC, On-Board Computer) 시뮬레이터 서버
 * - UDP 소켓 통신을 기반으로 지상국과 데이터를 주고받으며, 우주 환경의 물리적 제약과 통신 장애 상황을 모사.
 * * * [핵심 기능]
 * 1. FSM (유한 상태 머신): BOOT, NOMINAL, SAFE, EMERGENCY 4가지 상태를 기반으로
 * - 배터리 소모와 온도 변화 등의 물리 법칙을 시뮬레이션 수행. (온도 60도 초과 시 자율 EMERGENCY 돌입)
 * 2. Store & Forward (로깅 및 덤프): 우주 방사선 등으로 인한 통신 단절(LOS) 발생 시,
 * - 텔레메트리(TM) 데이터를 버리지 않고 플래시 메모리(Queue)에 저장했다가 통신 복구(AOS) 시 일괄 전송.
 * 3. 무결성 검증: CRC-16-XMODEM 알고리즘을 사용하여 패킷의 변조 및 오류를 검사.
 * - 기존 분리되어 있던 궤도 스레드를 TM 스레드로 통합하여 데이터 갱신 타이밍 동기화.
 * - 0도 경계선 문제 해결을 위해 '중심각 최단 거리(Angular Difference)' 로직 도입.
 * - 대역폭 최적화를 위해 궤도 각도에 *10 스케일링을 적용하여 short(2바이트)로 캐스팅하여 전송.
 * - 서버 기동 시 초기 궤도 각도를 기반으로 통신 상태(AOS/LOS) 자동 동적 초기화.
 * - 텔레메트리(TM) 패킷을 실시간(RT: 0x05)과 과거 덤프(PB: 0x06)로 완벽 분리하여 지상국 자동 관제 꼬임 방지.
 */
@Slf4j
public class SatelliteOBC {

	private static volatile SatelliteState currentState = SatelliteState.BOOT;

	private static volatile InetAddress lastClientAddress = null;
	private static volatile int lastClientPort = 0;
	private static short tmSeq = 1;    // TM 전용 sequence

	private static volatile short battery = 100;         // 100%
	private static volatile short temperature = 15;       // 15도

	// 통신 두절 시 데이터를 보관할 플래시 메모리 (Queue)
	private static final Queue<byte[]> flashMemory = new ConcurrentLinkedQueue<>();
	private static final int MAX_MEMORY_CAPACITY = 100; // 최대 100개의 패킷만 보관

	// ==========================================
	// 궤도 시뮬레이션 전역 변수 & 상수
	// ==========================================
	private static final double ORBIT_SPEED_PER_SEC = 6.0;  // 1초당 이동 각도 (60초 지구 1바퀴 : 360도)
	private static final double GS_CENTER_ANGLE = 180.0;    // 지상국 중심 각도
	private static final double VISIBILITY_HALF_ANGLE = 60.0; // 지상국과 통신 가능한 가시권 각도 (앞뒤 60도, 즉 120~240도 구간)

	// 현재 궤도 각도 (시작하자마자 통신을 확인하기 위해 175도로 세팅)
	private static volatile double currentOrbitAngle = 175.0;

	// 초기 각도를 바탕으로 과거 가시권 상태 세팅
	private static volatile boolean wasInCoverage = calculateInitialCoverage(currentOrbitAngle);

	// 궤도 기반 통신 단절 추적 변수
	private static volatile boolean isCommunicationLost = !wasInCoverage;

	// 초기 상태 계산 함수
	private static boolean calculateInitialCoverage(double orbitAngle) {
		return angularDifference(orbitAngle, GS_CENTER_ANGLE) <= VISIBILITY_HALF_ANGLE;
	}

	// 두 각도 사이의 최단 거리 계산 : a = 위성 위치, b = 지상국 중심 위치
	private static double angularDifference(double a, double b) {
		double diff = Math.abs(a - b) % 360.0; // 각도 뺀 값을 360도로 나눗셈 연산
		return diff > 180.0 ? 360.0 - diff : diff;
	}

	// 응답 패킷 전송용 메서드 (ACK or NAK)
	private static void sendResponse(DatagramSocket socket, InetAddress clientAddress, int clientPort, short seq, boolean isAck) {
		try {
			// 헤더 조립 (8바이트)
			byte[] header = new byte[8];
			// ByteBuffer.wrap(header): 원본 배열(header)의 메모리 주소를 직접 참조하여 Buffer 조작 시 배열 데이터가 실시간으로 동기화
			ByteBuffer buffer = ByteBuffer.wrap(header);
			buffer.order(ByteOrder.BIG_ENDIAN);

			buffer.putShort((short) 0xCAFE);         // Magic
			buffer.put((byte) 0x01);                 // Ver
			buffer.put((byte) (isAck ? 0x00 : 0xFF));  // 응답 Type: 참이면 0x00(ACK), 거짓이면 0xFF(NAK)
			buffer.putShort(seq);                    // 받은 Sequence 그대로 반환
			buffer.putShort((short) 0);              // Payload Length

			// CRC 계산
			int crc = Crc16Utils.calculateCrc16Xmodem(header, 8);

			// 최종 패킷 조립 (10바이트 = 헤더 8 + CRC 2)
			ByteBuffer packetBuffer = ByteBuffer.allocate(10);
			packetBuffer.order(ByteOrder.BIG_ENDIAN);
			packetBuffer.put(header);
			packetBuffer.putShort((short) crc);

			// 전송
			byte[] sendData = packetBuffer.array();
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, clientAddress, clientPort);
			socket.send(sendPacket);

			log.info("🚀 지상국으로 [{}] 패킷 전송 완료 (Seq: {})", isAck ? "ACK" : "NAK", seq);
		} catch (Exception e) {
			log.error("응답 패킷 전송 중 오류 발생: ", e);
		}
	}

	/**
	 * 패킷 데이터(byte 배열) 만들어내는 함수 : 통신이 끊겼을 때 내부 메모리에 TM을 저장하기 위해 데이터 조립
	 * [배터리, 온도, 현재 모드, 궤도 위치]를 패킷으로 만들어 반환
	 * RT or PB 인지 확인하기 위해 isRealTime 파라미터로
	 * @param isRealTime true면 RT(0x05), false면 PB(0x06
	 * @return byte[]
	 */
	private static byte[] buildTelemetryPacket(boolean isRealTime) {
		byte[] header = new byte[8];
		ByteBuffer buffer = ByteBuffer.wrap(header);
		buffer.order(ByteOrder.BIG_ENDIAN);
		buffer.putShort((short) 0xCAFE);
		buffer.put((byte) 0x01);

		// [RT/PB 분리] 실시간 여부에 따라 패킷 타입을 명확히 분리하여 헤더에 기록
		buffer.put((byte) (isRealTime ? 0x05 : 0x06));

		buffer.putShort(tmSeq++);

		// Payload 길이: 7 bytes라고 알려주는 용도
		buffer.putShort((short) 7);

		// 전체 패킷 크기: 헤더 8 + 페이로드 7 + CRC 2 = 17 bytes
		ByteBuffer packetBuffer = ByteBuffer.allocate(17);
		packetBuffer.order(ByteOrder.BIG_ENDIAN);
		packetBuffer.put(header);
		packetBuffer.putShort(battery);
		packetBuffer.putShort(temperature);

		// 1 byte : FSM 상태를 바이트로 변환해서 Payload 데이터에 담음
		byte modeByte = switch (currentState) {
			case BOOT -> 0x40;
			case SAFE -> 0x10;
			case EMERGENCY -> 0x30;
			case NOMINAL -> 0x20;
		};
		packetBuffer.put(modeByte);

		// Scaling: 소수점 이하를 살리기 위해 10을 곱한 뒤 short(2 bytes)로 캐스팅하여 전송
		short scaledAngle = (short) Math.round(currentOrbitAngle * 10.0);
		packetBuffer.putShort(scaledAngle);

		// CRC 계산 : 헤더 8 + 페이로드 7 = 15 bytes
		int crc = Crc16Utils.calculateCrc16Xmodem(packetBuffer.array(), 15);
		packetBuffer.putShort((short) crc);

		return packetBuffer.array();
	}

	public static void main(String[] args) {
		int port = 9000;

		try (DatagramSocket socket = new DatagramSocket(port)) {
			log.info("위성 시스템 시작 - 지상국 명령 대기 중... (Port: {})", port);
			log.info("초기 궤도 세팅: {}도 (통신 단절 여부: {})", currentOrbitAngle, isCommunicationLost);

			// TM 주기적으로 쏘는 백그라운드 Thread : 1초에 한 번씩 궤도 갱신, FSM 업데이트 및 TM 전송
			Thread tmThread = new Thread(() -> {
				int bootTimer = 0;
				while (true) {
					try {
						Thread.sleep(1000); // 1초 대기

						// ========================================================
						// 1. 궤도 각도 증가 및 보정 (360도 회전)
						currentOrbitAngle += ORBIT_SPEED_PER_SEC;
						if (currentOrbitAngle >= 360.0) {
							currentOrbitAngle -= 360.0;
						}

						// 2. 현재 각도가 지상국 통신 범위 안에 있는지 판별
						boolean isInCoverage = angularDifference(currentOrbitAngle, GS_CENTER_ANGLE) <= VISIBILITY_HALF_ANGLE;

						// 3. 상태가 전환되는 순간(AOS/LOS) 감지 및 로그 출력 : 지금과 1초 전 같은 통신 범위 각도 내에 있는지 확인
						if (isInCoverage && !wasInCoverage) {  // isInCoverage: True / wasInCoverage : False면 아래 실행 : 즉,True && !False
							log.info("📡 [AOS 진입] 위성이 지상국 가시권에 진입했습니다. (현재: {}도)", String.format("%.1f", currentOrbitAngle));
							isCommunicationLost = false;                     // 통신 복구
						} else if (!isInCoverage && wasInCoverage) {         // 즉 !False && True 인 경우에 아래 실행
							log.warn("💥 [LOS 진입] 위성이 지상국 가시권을 벗어났습니다. 통신이 두절됩니다. (현재: {}도)", String.format("%.1f", currentOrbitAngle));
							isCommunicationLost = true;                		 // 통신 단절
						}
						wasInCoverage = isInCoverage; // 다음 초 계산을 위해 현재 상태 저장

						// ========================================================
						// 2. FSM(Finite State Machine) 상태 변경 로직
						switch (currentState) {
							case BOOT: // 위성에 전원이 들어오는 최초 상태
								bootTimer++;
								if (bootTimer >= 3) {  // Boot 상태에서 3초 대기 후 NOMINAL 모드로 전환
									log.info("💻 [FSM] 시스템 부팅 완료. NOMINAL 모드로 자율 전환.");
									currentState = SatelliteState.NOMINAL;
								}
								break;
							case NOMINAL:
								battery = (short) Math.max(0, battery - 4);
								temperature = (short) Math.min(80, temperature + 4);

								if (temperature >= 60) {
									log.error("🔥 [FSM] 임계 온도 초과! 시스템 보호를 위해 EMERGENCY 모드로 강제 전환");
									currentState = SatelliteState.EMERGENCY;
								}
								break;
							case EMERGENCY:
								// 시스템은 꺼져있지만 태양광 패널로 인해 배터리 충전
								battery = (short) Math.min(100, battery + 1);
								temperature = (short) Math.max(-10, temperature - 5);
								break;
							case SAFE:
								battery = (short) Math.min(100, battery + 5);
								temperature = (short) Math.max(-10, temperature - 3);
								break;
						}

						// ========================================================
						// 3. TM(Telemetry) 전송 및 로깅 로직
						// 지상국에서 데이터를 전송하여 주소를 알 때만 TM 전송.
						if (lastClientAddress != null) {
							// [RT/PB 분리] LOS/AOS 상황에 맞춰 생성 && AOS & LOS 상태에 따른 TM 통신 처리 분기
							if (isCommunicationLost) {
								// 1. 통신 두절 (LOS) 상태: 전송하지 않고 메모리에 저장만 함
								// 통신 단절 중 발생하는 데이터는 과거 데이터이므로 false를 넘겨 header type에 0x06으로 저장
								byte[] pbTmData = buildTelemetryPacket(false);

								if (flashMemory.size() < MAX_MEMORY_CAPACITY) {
									flashMemory.offer(pbTmData);
									log.info("💾 [로깅 중] 전송 불가(LOS). 플래시 메모리에 저장합니다. (저장된 개수: {}, 궤도: {}도)",
										flashMemory.size(), String.format("%.1f", currentOrbitAngle));
								} else {
									flashMemory.poll(); 			// 꽉 찼으면 오래된 TM 메세지 삭제
									flashMemory.offer(pbTmData);
								}
							} else {
								// 2. 통신 정상 (AOS) 상태:
								// 만약 통신이 방금 복구되어서 메모리에 밀린 데이터가 있다면 먼저 전송
								while (!flashMemory.isEmpty()) {
									byte[] dumpData = flashMemory.poll();
									DatagramPacket dumpPacket = new DatagramPacket(dumpData, dumpData.length, lastClientAddress, lastClientPort);
									socket.send(dumpPacket);
									log.info("📥 [DUMP] 저장된 과거 TM 데이터를 전송합니다. (남은 데이터: {})", flashMemory.size());
									Thread.sleep(50); // 패킷이 너무 빨리 가서 파이썬 소켓 버퍼가 넘치는 것을 방지
								}

								// 밀린 걸 다 보냈거나 원래 밀린 게 없었다면, 현재 만든 데이터를 실시간으로 전송
								// 실시간으로 보내는 데이터이므로 isRealTime에 true를 넘겨 0x05(RT)로 전송하도록
								byte[] rtTmData = buildTelemetryPacket(true);
								DatagramPacket tmPacket = new DatagramPacket(rtTmData, rtTmData.length, lastClientAddress, lastClientPort);
								socket.send(tmPacket);
							}
						}
					} catch (InterruptedException e) {
						break; // 스레드 중단
					} catch (Exception e) {
						log.error("TM 스레드 오류", e);
					}
				}
			});
			tmThread.setDaemon(true); // 메인 스레드 종료 시 같이 쓰레드도 종료되도록 설정
			tmThread.start();

			byte[] receiveBuffer = new byte[1024];

			// 지상국에서 전송하는 TC Packet을 받아 Magic byte와 CRC 확인 후 처리(currentState 변경 & Ack 전송)
			while (true) {
				DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
				socket.receive(packet); // 여기서 패킷이 올 때까지 기다림

				// 패킷을 보낸 지상국의 IP와 포트 번호 확보 (답장할 주소 : ACK or NAK)
				// 지상국 주소 업데이트 (이제 백그라운드 스레드도 이 주소를 보고 TM 전송)
				lastClientAddress = packet.getAddress();
				lastClientPort = packet.getPort();

				// 패킷 파싱, 패킷에 들어온 데이터 길이만큼만 사용
				ByteBuffer buffer = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
				buffer.order(ByteOrder.BIG_ENDIAN);

				short magic = buffer.getShort();   // 2byte만 읽음 : 지상국과의 통신인지 0xCAFE인지 확인
				if (magic != (short) 0xCAFE) {
					log.warn("잘못된 Magic Number 수신됨: 0x{}", Integer.toHexString(magic & 0xFFFF));
					continue;
				}

				// 수신된 데이터 전체에서 8바이트(헤더)만 잘라서 CRC 계산
				byte[] rawData = packet.getData();
				int calculatedCrc = Crc16Utils.calculateCrc16Xmodem(rawData, 8); // 앞의 8바이트만 계산

				byte ver = buffer.get();
				byte type = buffer.get();
				short seq = buffer.getShort();
				short len = buffer.getShort();

				// 지상국이 보낸 CRC 추출 및 비교
				// 현재 버퍼의 포인터는 8번 인덱스를 가리키고 있으므로, 바로 getShort()를 하면 CRC 2바이트를 읽음.
				short receivedCrcShort = buffer.getShort();    //getShort()는 2바이트를 읽어 CRC 2 바이트를 저장
				int receivedCrc = receivedCrcShort & 0xFFFF;   // 부호 문제 방지 위해 int로 변환

				log.info("--- 패킷 검증 ---");
				log.info("수신된 CRC: 0x{}", String.format("%04X", receivedCrc));
				log.info("계산된 CRC: 0x{}", String.format("%04X", calculatedCrc));

				if (receivedCrc != calculatedCrc) {
					log.error("CRC 검증 실패! (수신: 0x{}, 계산: 0x{})", String.format("%04X", receivedCrc), String.format("%04X", calculatedCrc));
					sendResponse(socket, lastClientAddress, lastClientPort, seq, false); // NAK 전송
					continue;
				}

				// 통신 두절 시뮬레이션 중에는 지상국 명령(TC)도 무시
				if (isCommunicationLost) {
					log.warn("🚫 [LOS 상태] 궤도 이탈로 인해 수신된 명령(Seq:{})을 처리할 수 없습니다.", seq);
					continue;
				}

             /* --- 정상 패킷 처리 ---
             log.info("✅ 위성: CRC 일치! [정상 패킷 수신]");
             log.info("Version: {}", ver);
             log.info("Type: 0x{}", Integer.toHexString(type & 0xFF));
             log.info("Sequence: {}", seq);
             log.info("Payload Length: {}", len);
             log.info("======================================");*/

				if (type == (byte) 0x10) {
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

				sendResponse(socket, lastClientAddress, lastClientPort, seq, true); // ACK 전송
			}
		} catch (Exception e) {
			log.error("위성 메인 루프에서 예외 발생: ", e);
		}
	}
}