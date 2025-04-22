# Passcode 3.0

![Java](https://img.shields.io/badge/Java-17%2B-red)
![JavaFX](https://img.shields.io/badge/JavaFX-UI-blue)
![License](https://img.shields.io/badge/License-MIT-green)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen)](https://github.com/your-repo/passcode-java)

**Passcode**는 Java와 JavaFX로 개발된 현대적이고 안전한 파일 암호화 애플리케이션입니다. AES-256 암호화를 통해 파일을 보호하며, 병렬 처리, 키보드 단축키, 디스크 공간 확인 등 편리한 기능을 제공합니다. 개인 및 팀 사용에 최적화된 직관적인 UI로 데이터를 안전하게 관리하세요.

---

## 목차

- [기능](#기능)
- [설치](#설치)
- [사용법](#사용법)
- [라이선스](#라이선스)

---

## 기능

- **안전한 암호화**: AES-256 CBC 모드와 PBKDF2(65536 반복)로 강력한 키 생성, 파일 보호.
- **병렬 처리**: 대용량 파일을 청크 단위로 멀티스레드 처리, 멀티코어 CPU 활용(4코어 기준 2~3배 속도 향상).
- **사용자 친화적 UI**:
  - JavaFX 기반, `Noto Sans KR` 폰트로 깔끔하고 현대적인 디자인.
  - 파일/폴더 드래그 앤 드롭 지원.
  - 키보드 단축키(Ctrl+E: 암호화, Ctrl+D: 복호화)로 빠른 작업.
  - 실시간 메모리 사용량 모니터링.
- **디스크 공간 확인**: 암호화/복호화 전 디스크 공간 점검, 작업 실패 방지.
- **작업 우선순위**: 소규모 파일 우선 처리로 혼합 작업 지연 최소화.
- **안전 삭제**: 파일 삭제 전 0으로 덮어씌워 데이터 복구 방지.
- **키 관리**: `.key` 파일로 암호화 키 생성 및 로드, 안전한 저장.

---

## 설치

### 요구사항
- **Java 17** 이상 (Java 21 호환).
- **Maven**: 의존성 관리.
- **운영체제**: Windows

---

## 사용법

1. **애플리케이션 실행**:
   - Maven 또는 JAR로 실행해 JavaFX UI를 엽니다.

2. **키 생성 또는 로드**:
   - "키 생성" (Ctrl+K) 클릭, 비밀번호 입력 후 `.key` 파일 저장.
   - "키 로드" (Ctrl+L) 클릭, 기존 `.key` 파일 선택.

3. **파일 암호화**:
   - 파일/폴더를 드래그 앤 드롭하거나 "폴더 열기" (Ctrl+F)로 선택.
   - 청크 크기(예: 1GB) 선택, 병렬 처리 최적화.
   - "암호화" (Ctrl+E) 클릭, `.lock` 파일 생성.

4. **파일 복호화**:
   - `.lock` 파일 선택.
   - "복호화" (Ctrl+D) 클릭, 원본 파일 복원.

5. **안전 삭제**:
   - 파일 선택 후 "안전 삭제" 클릭, 데이터 완전 제거.

6. **진행 상황 확인**:
   - UI에서 메모리 사용량과 작업 진행률 실시간 확인.

> **팁**: Ctrl+R로 파일 목록 새로고침하세요.

---

## 라이선스

Passcode는 다음 구성 요소를 사용합니다:

- **JavaFX**: 사용자 인터페이스 (Apache License 2.0)
- **Ikonli**: 아이콘 (Apache License 2.0)
- **JCA (Java Cryptography Architecture)**: 암호화/복호화 (Oracle Binary Code License)
- **Noto Sans KR**: 폰트 (SIL Open Font License 1.1)

전체 라이선스 텍스트는 `LICENSE` 파일 또는 아래에서 확인:
- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [SIL OFL 1.1](https://scripts.sil.org/OFL)
- [Oracle BCL](https://www.oracle.com/downloads/licenses/binary-code-license.html)

---
