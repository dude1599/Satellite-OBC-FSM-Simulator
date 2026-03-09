import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import lombok.extern.slf4j.Slf4j;

/**
 * 위성 측 : OBS / Server
 */
@Slf4j
public class SatelliteOBC {

	private static volatile SatelliteState currentState = SatelliteState.BOOT;

	private static volatile InetAddress lastClientAddress = null;
	private static volatile int lastClientPort = 0;
	private static short tmSeq = 1; 	// TM 전용 sequence

	private static volatile short battery = 100;		// 100%
	private static volatile short temperature = 15;		// 15도

	// 통신 두절 시 데이터를 보관할 플래시 메모리 (Queue)
	private static final Queue<byte[]> flashMemory = new ConcurrentLinkedQueue<>();
	private static final int MAX_MEMORY_CAPACITY = 100; // 최대 100개의 패킷만 보관
	// 비정기적 통신 상태 시뮬레이션을 위한 변수
	private static volatile boolean isCommunicationLost = false;
	private static final Random random = new Random();

	// 응답 패킷 전송용 메서드 추가 (ACK or NAK)
	private static void sendResponse(DatagramSocket socket, InetAddress clientAddress, int clientPort, short seq, boolean isAck) {
		try {
			// 헤더 조립 (8바이트)
			byte[] header = new byte[8];
			// ByteBuffer.wrap(header): 원본 배열(header)의 메모리 주소를 직접 참조하여 Buffer 조작 시 배열 데이터가 실시간으로 동기화됨.
			ByteBuffer buffer = ByteBuffer.wrap(header);
			buffer.order(ByteOrder.BIG_ENDIAN);

			buffer.putShort((short) 0xCAFE); 			// Magic
			buffer.put((byte) 0x01);         			// Ver
			buffer.put((byte) (isAck ? 0x00 : 0xFF)); 	// 응답 Type: 참이면 0x00(ACK), 거짓이면 0xFF(NAK)
			buffer.putShort(seq);            			// 받은 Sequence 그대로 반환
			buffer.putShort((short) 0);      			// Payload Length

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

	// 패킷 데이터(byte 배열)만 만들어내는 함수 : 통신이 끊겼을 때 내부 메모리에 TM을 저장하기 위해 데이터 조립만 하는 함수로 변경.
	private static byte[] buildTelemetryPacket() {
		byte[] header = new byte[8];
		ByteBuffer buffer = ByteBuffer.wrap(header);
		buffer.order(ByteOrder.BIG_ENDIAN);
		buffer.putShort((short) 0xCAFE);
		buffer.put((byte) 0x01);
		buffer.put((byte) 0x05);
		buffer.putShort(tmSeq++);
		buffer.putShort((short) 5);

		ByteBuffer packetBuffer = ByteBuffer.allocate(15);
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

		int crc = Crc16Utils.calculateCrc16Xmodem(packetBuffer.array(), 13);
		packetBuffer.putShort((short) crc);

		return packetBuffer.array();
	}

	public static void main(String[] args) {
		int port = 9000;

		try (DatagramSocket socket = new DatagramSocket(port)) {
			log.info("위성 시스템 시작 - 지상국 명령 대기 중... (Port: {})", port);

			Thread faultInjectionThread = new Thread(() -> {
				while (true) {
					try {
						// 10초 ~ 20초 사이 랜덤하게 정상 통신 유지
						int normalDuration = 10000 + random.nextInt(10000);
						Thread.sleep(normalDuration);

						log.warn("💥 [장애 발생] 우주 방사선 영향으로 일시적인 통신 단절(LOS) 발생!");
						isCommunicationLost = true;

						// 3초 ~ 7초 사이 랜덤하게 통신 두절 유지
						int lostDuration = 3000 + random.nextInt(4000);
						Thread.sleep(lostDuration);

						log.info("📡 [장애 복구] 통신 모듈 정상화 (AOS). 연결이 복구되었습니다.");
						isCommunicationLost = false;

					} catch (InterruptedException e) {
						break;
					}
				}
			});
			faultInjectionThread.setDaemon(true);
			faultInjectionThread.start();

			// TM 주기적으로 쏘는 백그라운드 스레드 시작
			Thread tmThread = new Thread(() -> {
				int bootTimer = 0;
				while (true) {
					try {
						Thread.sleep(1000); // 1초 대기

						// FSM(Finite State Machine) 상태 변경 로직
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

								if (temperature >= 60) {
									log.error("🔥 [FSM] 임계 온도 초과! 시스템 보호를 위해 EMERGENCY 모드로 강제 전환");
									currentState = SatelliteState.EMERGENCY;
								}
								break;
							case SAFE:
								battery = (short) Math.min(100, battery + 5);
								temperature = (short) Math.max(-10, temperature - 3);
								break;
							case EMERGENCY:
								// 시스템은 꺼져있지만 태양광 패널로 인해 배터리를 천천히 충전
								battery = (short) Math.min(100, battery + 1);
								temperature = (short) Math.max(-10, temperature - 5);
								break;
						}
						// 지상국에서 데이터를 전송하여 주소를 알 때만 TM 전송.
						if (lastClientAddress != null) {
							byte[] currentTmData = buildTelemetryPacket(); // 1초마다 데이터 생성

							// 통신 상태에 따른 처리 분기
							if (isCommunicationLost) {
								// 1. 통신 두절 (LOS) 상태: 전송하지 않고 메모리에 저장만 함
								if (flashMemory.size() < MAX_MEMORY_CAPACITY) {
									flashMemory.offer(currentTmData);
									log.info("💾 [로깅 중] 전송 불가. 패킷을 플래시 메모리에 저장합니다. (저장된 개수: {})", flashMemory.size());
								} else {
									flashMemory.poll(); // 꽉 찼으면 오래된 TM 메세지 삭제
									flashMemory.offer(currentTmData);
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

								// 밀린 걸 다 보냈거나 원래 밀린 게 없었다면, 현재 만든 데이터를 실시간으로 보냄
								DatagramPacket tmPacket = new DatagramPacket(currentTmData, currentTmData.length, lastClientAddress, lastClientPort);
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

			while (true) {
				DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
				socket.receive(packet); // 여기서 패킷이 올 때까지 기다림

				// 패킷을 보낸 지상국의 IP와 포트 번호 확보 (답장할 주소 : ACK or NAK)
				// 지상국 주소 업데이트 (이제 백그라운드 스레드도 이 주소를 보고 텔레메트리를 전송)
				lastClientAddress = packet.getAddress();
				lastClientPort = packet.getPort();

				// 패킷 파싱
				ByteBuffer buffer = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength()); //패킷에 들어온 데이터 길이만큼만 사용
				buffer.order(ByteOrder.BIG_ENDIAN);

				short magic = buffer.getShort();	// 2byte만 읽음 : 지상국과의 통신인지 0xCAFE인지 확인
				if (magic != (short) 0xCAFE) {
					log.warn("잘못된 Magic Number 수신됨: 0x{}", Integer.toHexString(magic & 0xFFFF));
					continue;
				}

				// 수신된 데이터 전체에서 8바이트(헤더)만 잘라서 CRC 계산 ---
				byte[] rawData = packet.getData();
				int calculatedCrc = Crc16Utils.calculateCrc16Xmodem(rawData, 8); // 앞의 8바이트만 계산

				byte ver = buffer.get();
				byte type = buffer.get();
				short seq = buffer.getShort();
				short len = buffer.getShort();

				// 지상국이 보낸 CRC 추출 및 비교
				// 현재 버퍼의 포인터는 8번 인덱스를 가리키고 있으므로, 바로 getShort()를 하면 CRC 2바이트를 읽는다.
				short receivedCrcShort = buffer.getShort();	//getShort()는 2바이트를 읽어 CRC 2 바이트를 저장
				int receivedCrc = receivedCrcShort & 0xFFFF; // 부호 문제 방지 위해 int로 변환

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
					log.warn("🚫 [통신 단절] 수신된 명령(Seq:{})을 처리할 수 없습니다.", seq);
					continue;
				}

				// --- 정상 패킷 처리 ---
				log.info("✅ 위성: CRC 일치! [정상 패킷 수신]");
				log.info("Version: {}", ver);
				log.info("Type: 0x{}", Integer.toHexString(type & 0xFF));
				log.info("Sequence: {}", seq);
				log.info("Payload Length: {}", len);
				log.info("======================================");


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