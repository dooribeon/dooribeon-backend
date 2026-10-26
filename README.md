# 두리번

예산과 생활거점까지의 이동 부담을 비교해 거주할 동네를 추천하는 서비스

## 기술 스택
Java 21 · Spring Boot 4.1.2 · Gradle

## 실행
1. JDK 21 준비
2. IntelliJ에서 Annotation Processing 활성화 (Lombok)
3. `DooribeonBackendApplication` 실행 → `localhost:8080`

## 패키지
```
com.dooribeon
├── condition      자연어 → 조건 (ChatKHU)
├── recommend      추천 계산
├── route          경로 조회
├── neighborhood   동네 데이터
└── global         공통 설정
```
