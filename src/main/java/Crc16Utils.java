/**
 * 통신 무결성 검증을 위한 CRC-16-XMODEM 계산 클래스
 */
public class Crc16Utils {

	// 객체 생성 방지
	private Crc16Utils() {}

	// CRC16-XMODEM 계산 함수 추가 : 헤더만 갖고 계산함.
	// byte를 int로 연산할 때 부호 확장을 하게 되고 기존 비트들은 오른쪽 끝에 가만히 있고, 왼쪽에 새로 생긴 빈 공간을 원본의 MSB로 채운다.
	public static int calculateCrc16Xmodem(byte[] data, int length) {
		int crc = 0x0000;
		for (int i = 0; i < length; i++) {
			// byte를 int로 변환할 때 음수 확장을 막기 위해 & 0xFF 사용
			crc ^= ((data[i] & 0xFF) << 8);	// Java에서는 비트이동이나 산술연산을 하면 이 byte를 int(32bit)로 자동 승격시켜서 부호 확장이 발생한다.
			for (int j = 0; j < 8; j++) {
				if ((crc & 0x8000) != 0) {	// CRC의 첫 비트(MSB)가 1이면 나눗셈(나머지 : XOR)가 가능하다.
					crc = ((crc << 1) ^ 0x1021);
				} else {
					crc <<= 1;
				}
				crc &= 0xFFFF; // 16비트 유지
			}
		}
		return crc;
	}
}