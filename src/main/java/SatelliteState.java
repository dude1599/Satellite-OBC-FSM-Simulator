/**
 * 위성의 운용 상태를 정의하는 FSM(Finite State Machine) Enum
 */
public enum SatelliteState {
	BOOT,       // 시스템 부팅 및 초기화 단계
	NOMINAL,    // 정상 임무 수행 단계 (배터리 소모, 온도 상승)
	SAFE,       // 안전 모드 (최소 전력, 태양광 충전)
	EMERGENCY   // 비상 사태 (온도 초과 등 치명적 오류 발생 시 강제 돌입)
}