import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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

	// 1초마다 TM(상태 데이터)를 쏘는 함수
	private static void sendTelemetry(DatagramSocket socket) {
		try {
			// Type 0x05 = TM, Payload는 4바이트(배터리 2 + 온도 2)
			byte[] header = new byte[8];
			ByteBuffer buffer = ByteBuffer.wrap(header);
			buffer.order(ByteOrder.BIG_ENDIAN);
			buffer.putShort((short) 0xCAFE);	// Magic : buffer.putShort() = 2바이트(16bit) 할당
			buffer.put((byte) 0x01);			// Ver	 : buffer.put() = 1바이트(8bit) 할당
			buffer.put((byte) 0x05); 			// TM Type : 0x05 = TM
			buffer.putShort(tmSeq++); 			// 보낼 때마다 시퀀스 1씩 증가

			buffer.putShort((short) 5); // Payload 길이 5바이트 : 배터리 2 + 온도 2 + 모드 1(Safe or Nominal)


			// 총 15바이트 할당 (헤더 8 + 페이로드 5 + 마지막에 CRC 2)
			ByteBuffer packetBuffer = ByteBuffer.allocate(15);
			packetBuffer.order(ByteOrder.BIG_ENDIAN);
			packetBuffer.put(header);       // 8byte 헤더 넣기
			packetBuffer.putShort(battery); // 페이로드 1: 배터리(2 byte)
			packetBuffer.putShort(temperature); // 페이로드 2: 온도(2 byte)

			byte modeByte = switch (currentState) {
				case BOOT -> 0x40;
				case SAFE -> 0x10;
				case EMERGENCY -> 0x30;
				case NOMINAL -> 0x20;
			};
			packetBuffer.put(modeByte);

			// 배열(13바이트)을 CRC 돌림
			int crc = Crc16Utils.calculateCrc16Xmodem(packetBuffer.array(), 13);
			packetBuffer.putShort((short) crc); // 끝에 CRC 붙이기 : 총 15바이트 완성

			byte[] sendData = packetBuffer.array();
			DatagramPacket tmPacket = new DatagramPacket(sendData, sendData.length, lastClientAddress, lastClientPort);
			socket.send(tmPacket);

		} catch (Exception e) {
			log.error("TM 전송 중 오류: ", e);
		}
	}

	public static void main(String[] args) {
		int port = 9000;

		try (DatagramSocket socket = new DatagramSocket(port)) {
			log.info("위성 시스템 시작 - 지상국 명령 대기 중... (Port: {})", port);

			// TM 주기적으로 쏘는 백그라운드 스레드 시작
			Thread tmThread = new Thread(() -> {
				int bootTimer = 0;
				while (true) {
					try {
						Thread.sleep(1000); // 1초 대기

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
							sendTelemetry(socket);
						}
					} catch (InterruptedException e) {
						break; // 스레드 중단
					} catch (Exception e) {
						log.error("TM 스레드 오류", e);
					}
				}
			});
			tmThread.setDaemon(true); // 메인 스레드 종료 시 같이 죽도록 설정
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

				// --- 정상 패킷 처리 ---
				log.info("✅ 위성: CRC 일치! [정상 패킷 수신]");
				log.info("Version: {}", ver);
				log.info("Type: 0x{}", Integer.toHexString(type & 0xFF));
				log.info("Sequence: {}", seq);
				log.info("Payload Length: {}", len);
				log.info("======================================");


				if (type == (byte) 0x10) {
					currentState = SatelliteState.SAFE;
					log.info("📡 [TC] 지상국 명령: SAFE 모드 전환.");
				} else if (type == (byte) 0x20) {
					currentState = SatelliteState.NOMINAL;
					log.info("📡 [TC] 지상국 명령: NOMINAL 모드 전환.");
				}
				log.info("현재 위성 상태: [{}]", currentState);

				sendResponse(socket, lastClientAddress, lastClientPort, seq, true); // ACK 전송
			}
		} catch (Exception e) {
			log.error("위성 메인 루프에서 예외 발생: ", e);
		}
	}
}