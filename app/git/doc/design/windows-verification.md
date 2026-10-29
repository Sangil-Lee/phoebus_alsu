# phoebus Git 앱: Windows 검증 계획

작성 기준: `phoebus_alsu` master `346bc9ec7` (EGit 동등 기능, reflog, Git 설정, 여러 커밋 선택까지)

## 1. 목표와 범위

* Linux에서 검증한 기능 전체(단위 테스트 106, UI 실행기 192)가 Windows에서도 같게 동작하는지 확인한다.
* Windows에서만 생기는 차이(줄바꿈 변환, 파일 잠금, 경로 대소문자, 한글 코드페이지, 파일 감시)를 별도 시나리오로 검증한다.
* 찾은 결함을 고치고, 사용자 문서에 Windows 관련 내용을 추가한다.

범위 밖: macOS, GPG 서명·ssh-agent(F9·F10, 의존성 결정 후), phoebus 다른 앱 자체의 Windows 문제.

## 2. 검증 환경

| 항목 | 값 | 비고 |
|---|---|---|
| OS | Windows 11 (빌드 26200) | 이 PC의 Windows 호스트. WSL에서 `cmd.exe`/`powershell.exe`로 실행 |
| JDK | 17.0.6 (Oracle) | phoebus 빌드와 같은 17 |
| Git for Windows | 2.52, 시스템 설정 `core.autocrlf=true` | Git for Windows 기본값. 비교·실행기용 |
| 코드페이지 | 949 (한국어) | `file.encoding`, 콘솔 출력 확인 대상 |
| 저장소 위치 | `C:` (NTFS) | `\\wsl$` 경로는 쓰지 않음 (감시·잠금 동작이 다름) |
| 격리 | `-Dphoebus.user`, `-Duser.home`을 테스트 폴더로 | 실제 `%USERPROFILE%\.gitconfig`, `.phoebus`, `.ssh`는 건드리지 않음 |

운영 환경과 다른 점이 있으면(예: Windows 10, `autocrlf=false` 정책, 네트워크 드라이브의 저장소) 해당 조합을 추가한다.

## 3. 코드 점검으로 미리 찾은 위험

| # | 위험 | 예상 증상 | 영향 기능 | 예상 |
|---|---|---|---|---|
| W1 | **줄바꿈 변환** (`autocrlf=true`): diff는 LF로 정규화되지만 작업 트리 파일은 CRLF | *Hunk/줄 버리기* 가 문맥 불일치로 "파일이 바뀌었습니다" 오류. *되돌리기(작업 트리만)*, *이 버전 열기*, 병합 도구 저장, 위젯 병합 저장이 LF로 씀. Linux에서 만든 patch 적용 실패 가능 | F1, F2, F6, F7, hunk 버리기 | **결함 가능성 높음** |
| W2 | **읽기 전용 파일 삭제**: *이 버전 열기* 가 꺼낸 파일을 읽기 전용으로 만듦 | Windows에서는 읽기 전용 파일을 지울 수 없어 종료 시 임시 폴더가 남음 | F2 | **결함 가능성 높음** |
| W3 | **파일 잠금**: 감시 중인 폴더, 다른 프로그램이 연 파일 | checkout·clean·브랜치 전환으로 폴더 삭제·이름 변경 실패, 열린 파일 덮어쓰기 실패 | 자동 새로 고침, F3 decorator, checkout 전반 | 확인 필요 |
| W4 | **경로 문자열 비교**: 대소문자, 드라이브 문자(`C:` / `c:`) | 열린 Git 탭을 못 찾아 새 탭을 엶, 저장 안 한 편집기 경고 누락, A4 열린 파일 다시 불러오기 누락 | Git에서 보기, 파일 이력, 되돌리기 경고, 편집기 연계 | 결함 가능성 중간 |
| W5 | **한글**: 코드페이지 949 | 커밋 메시지·파일 이름·로그북 본문 깨짐, 콘솔/로그 깨짐 | 전체 | 확인 필요 |
| W6 | **SSH**: `%USERPROFILE%\.ssh`, Windows OpenSSH 키 형식, known_hosts | 키·호스트 키를 못 찾음 | 원격 인증 | 확인 필요 |
| W7 | **HTTPS 인증**: Git Credential Manager | 명령행 git에 저장된 인증을 앱이 쓰지 않음(phoebus 보안 저장소 사용) | 원격 인증 | 동작 차이, 문서화 |
| W8 | `core.filemode=false`, `core.symlinks=false` | 실행 비트 변경이 변경으로 나오거나 안 나옴, 심볼릭 링크가 텍스트 파일 | 파일 상태 | 확인 필요 |
| W9 | 긴 경로(260자), `core.longpaths` | 깊은 폴더의 checkout·꺼내기 실패 | 전체 | 확인 필요 |
| W10 | 대소문자만 바뀐 이름 변경 (`Main.bob` → `main.bob`) | 변경이 안 보이거나 삭제+추가로 나옴 | 파일 상태, 비교 | 확인 필요 |
| W11 | 네트워크 드라이브(SMB)의 저장소 | 파일 감시 알림이 없거나 늦음 | 자동 새로 고침, F3 | 확인 필요 (사내 환경) |
| W12 | 실행기 자체: bash 준비 스크립트, `git` 출력 문자셋 | 실행기 오류 | 검증 도구 | 이식 필요 |
| W13 | JavaFX Windows용 jar가 오프라인 저장소에 없음 (현재 Linux용만 있음) | Windows에서 phoebus가 뜨지 않음 | 실행 | 준비 작업 |

## 4. 단계별 계획

| 단계 | 내용 | 산출물 | 규모 |
|---|---|---|---|
| 0. 준비 | JavaFX Windows jar 받기(W13). `C:\Users\<사용자>\phoebus-git-test`에 product와 lib 구성, 실행 배치 파일. 격리된 `phoebus.user`, `user.home`. WSL에서 Windows JVM을 띄워 창이 Windows 데스크톱에 뜨는지 확인 | 실행 가능한 Windows 테스트 환경 | S |
| 1. 단위 테스트 | 테스트 클래스를 Windows JVM에서 JUnit Console로 실행 (NTFS 임시 폴더, Git for Windows로 `git am`·`submodule deinit` 확인). `autocrlf` 무시/적용 두 가지 | 단위 테스트 결과 | S |
| 2. 기존 UI 회귀 | E2E 73, 연계 21, 로그북 12, EGit 동등 86을 Windows에서 실행. 준비 스크립트는 Git Bash로 실행하고, 실행기의 `git` 출력은 UTF-8로 읽도록 수정(W12) | 회귀 결과 | M |
| 3. Windows 시나리오 | 새 실행기 단계 `win`: 아래 5절의 시나리오를 `autocrlf` true/input/false 세 조합으로 실행 | 시나리오 결과 | M |
| 4. 수동 확인 | 화면 배율 100/125/150%, 한글 글꼴, 기본 파일 선택 창, 대화상자 위치, Windows 런처에서 메뉴·툴바 확인. 스크린샷 | 확인 목록과 스크린샷 | S |
| 5. 수정·재검증 | W1·W2·W4 등 결함 수정 (예: 작업 트리에 쓸 때 JGit의 checkout 줄바꿈 변환 적용, 지우기 전 쓰기 권한 복구, 경로 비교를 `Files.isSameFile`/정규화 경로로). Linux 회귀도 함께 다시 실행 | 수정 커밋 | M |
| 6. 문서 | 사용자 문서에 Windows 항목(줄바꿈, 인증, 네트워크 드라이브, 긴 경로) 추가, 제한 사항 갱신 | 문서 | S |

규모: S ≈ 반나절~1일, M ≈ 1~2일 (실행과 수정 포함)

## 5. Windows 시나리오 (단계 3)

`autocrlf` 조합마다 실행한다. 기대 결과는 "Linux와 같은 결과 + 작업 트리 파일의 줄바꿈이 설정대로 유지됨"이다.

| # | 시나리오 | 확인 |
|---|---|---|
| WS-01 | CRLF 파일 수정 → diff, hunk stage/unstage/버리기, 줄 단위 stage/unstage/버리기 | 오류 없음, 작업 트리 CRLF 유지, `git diff`와 일치 |
| WS-02 | 되돌리기(작업 트리만 / index까지), 파일 이력에서 되돌리기 | 작업 트리 CRLF, `git status` 깨끗 |
| WS-03 | 텍스트 병합 도구, `.bob` 위젯 병합 저장 | 결과 CRLF, 해결 후 `git status` 정상 |
| WS-04 | Linux에서 만든 patch(LF) 적용, Windows에서 만든 patch를 `git am` | 적용 성공 |
| WS-05 | 이 버전 열기 → phoebus 종료 | 임시 폴더 삭제됨 |
| WS-06 | 자동 새로 고침 켠 채로 브랜치 전환·`git clean`·폴더 삭제가 있는 checkout | 실패 없음, 감시 갱신 |
| WS-07 | 화면을 Display Runtime/Editor로 연 채 pull·되돌리기 | 다시 불러오기 제안, 저장 안 한 편집기 경고 |
| WS-08 | 경로 대소문자·드라이브 문자를 달리해서 *Git에서 보기*, *파일 이력*, File Browser 표시 | 같은 Git 탭 사용, 표시 정상 |
| WS-09 | 한글 파일 이름·폴더·커밋 메시지·작성자, 로그북 본문 | 깨짐 없음, `git log`와 일치 |
| WS-10 | 실행 비트, 심볼릭 링크가 있는 저장소 (Linux에서 만든 것) | 불필요한 변경 표시 없음 |
| WS-11 | 대소문자만 바꾼 이름 변경 | rename으로 표시, 커밋 후 `git` 결과와 일치 |
| WS-12 | 260자 넘는 경로 (`core.longpaths=true`) | checkout·비교·꺼내기 동작, 안 되면 오류 문구 |
| WS-13 | SSH(`%USERPROFILE%\.ssh` 키, passphrase), HTTPS(보안 저장소) — GitHub 비공개 테스트 저장소 | fetch/push 성공 |
| WS-14 | submodule (clone 포함, deinit 후 재초기화) | Linux와 같음 |
| WS-15 | 큰 저장소(1만 커밋, 2천 폴더) 감시와 상태 읽기 | 시간·CPU가 Linux와 비슷한 수준 |

## 6. 합격 기준

* 단위 테스트, 기존 UI 회귀 192, Windows 시나리오가 `autocrlf=true`에서 모두 통과 (true가 Git for Windows 기본값).
* `input`, `false`에서도 데이터 손상(줄바꿈 뒤섞임, 잘못된 덮어쓰기)이 없음.
* 실행 로그에 예외·SEVERE 없음, 임시 파일이 남지 않음.
* Linux 회귀가 수정 후에도 그대로 통과.

## 7. 결정이 필요한 사항

1. **이 PC의 Windows에서 직접 실행해도 되는지**: `C:\Users\<사용자>\phoebus-git-test` 폴더를 만들고, 검증 중 Windows 데스크톱에 phoebus 창이 뜬다. 실제 `.gitconfig`, `.phoebus`, `.ssh`는 쓰지 않는다.
2. **JavaFX Windows용 jar 내려받기 허용**: Maven Central에서 `javafx-*-win.jar`(수십 MB)를 받는다. 오프라인 설치 번들에도 넣을지.
3. **사내 환경 항목 포함 여부**: 네트워크 드라이브(W11), 사내 Git 서버 SSH/HTTPS(W6·W7)는 사내망이 필요하다.
4. **운영 기준**: 운영 PC의 Windows 버전과 `core.autocrlf` 정책. 정책이 있으면 그 조합을 합격 기준으로 삼는다.
