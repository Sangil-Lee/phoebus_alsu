# phoebus Git 앱: EGit 동등 기능 설계

작성 기준: `phoebus_alsu` master `720886cf8` (app/git 2차 기능까지)
범위: Linux. **Windows 지원은 이번 설계에서 제외**하고, Windows에 영향이 있는 부분만 표시한다.

## 1. 목표

CS-Studio(Eclipse RCP)의 EGit과 비교해 부족한 아래 항목을 동등한 수준으로 갖춘다.

| # | 기능 | EGit의 해당 기능 | 우선순위 |
|---|---|---|---|
| F1 | 충돌 3-way 병합 도구 | Merge Tool (3-way Compare/Merge 편집기) | 높음 |
| F2 | 임의 버전 비교·복원 | Compare With / Replace With (HEAD, Index, Branch, Commit) | 높음 |
| F3 | File Browser Git 상태 표시 | Navigator label decorator | 높음 |
| F4 | Display Editor 연계 | Quick Diff, Show Revision Information(편집기 안 blame), Team 메뉴 | 중간 |
| F5 | Interactive rebase | Interactive Rebase 뷰 (pick/squash/fixup/reword/edit/drop, 순서 변경) | 중간 |
| F6 | 줄 단위 stage | Compare 편집기에서 줄 단위 index 편집 | 중간 |
| F7 | Patch 만들기·적용 | Create Patch / Apply Patch | 낮음 |
| F8 | Submodule | Submodule 지원 (clone, init, update, 상태) | 낮음 |
| F9 | GPG 서명 커밋 | Sign commits | 낮음 (의존성 추가 필요) |
| F10 | ssh-agent | ssh-agent / Pageant | 낮음 (의존성 추가 필요) |
| F11 | 원격 고급 설정 | Remote 설정 (fetch/push refspec, push URL, prune, tag 옵션) | 낮음 |

EPICS 운영에 맞춰 **EGit보다 나아갈 부분**도 함께 설계한다. `.bob` 파일의 위젯 단위 3-way 병합(F1),
편집기에서 HEAD 대비 바뀐 위젯 표시(F4), 위젯 단위 blame(F4)이 여기에 해당한다.

## 2. 공통 기반

모든 기능이 함께 쓰는 기반을 먼저 정리한다. 지금 `GitView.java`는 약 1,700줄로 커졌으므로,
기능을 넣기 전에 나누는 것이 좋다.

### 2.1 리비전 모델

비교·복원·병합에서 "어느 버전"을 가리키는 공용 타입.

```java
/** A version of the repository content */
sealed interface Revision
{
    record WorkTree() implements Revision {}          // 작업 트리
    record Index() implements Revision {}             // stage된 내용 (stage 0)
    record Commit(String id, String label) implements Revision {}  // 커밋, 브랜치, 태그 (label은 표시용)
    record Stage(int stage) implements Revision {}    // 충돌 중 index stage 1=base, 2=ours, 3=theirs
}
```

`GitRepository`에 리비전 단위 API를 추가한다. 기존 `getStagedVersions` 등은 이것으로 다시 구현한다.

```java
byte[] getContent(Revision revision, String path);
List<CommitFile> compare(Revision a, Revision b);                 // 두 리비전 사이 바뀐 파일 (이름 변경 감지)
String getDiff(Revision a, Revision b, String path_or_null);      // unified diff
Versions getVersions(Revision a, Revision b, String path);        // 위젯 diff 용
Revision resolve(String ref);                                     // "master", "origin/x", "v1.0", id → Commit
```

JGit: 커밋은 `CanonicalTreeParser`, index는 `DirCacheIterator`, 작업 트리는 `FileTreeIterator`,
충돌 stage는 `DirCache.getEntry(path)`를 stage 별로 찾는다.

### 2.2 화면 구성 정리

| 새 클래스 | 역할 |
|---|---|
| `CompareView` | 두 리비전의 파일 목록 + diff (F2). `CommitFilesPane`을 일반화 |
| `MergeView` | 충돌 파일 3-way 병합 (F1) |
| `RebaseTodoView` | interactive rebase 계획 편집 (F5) |
| `GitActions` | GitView의 작업 실행(`runAndRefresh`, 확인 창 등)을 분리 |
| `RevisionPicker` | 브랜치·태그·커밋 선택 공용 대화상자 |

### 2.3 외부 모듈 변경 원칙

F3과 F4는 phoebus의 다른 모듈(File Browser, Display Editor)을 고쳐야 한다.

* Git 앱에 의존하지 않는 **일반 확장 지점(SPI)** 으로만 추가한다.
  다른 앱도 쓸 수 있고, Git 앱이 없어도 동작해야 한다.
* 변경은 작게 하고, ControlSystemStudio/phoebus upstream에 PR로 보낼 수 있는 형태로 한다.
  그래야 phoebus_alsu가 upstream을 따라갈 때 충돌이 적다.

## 3. 기능별 설계

### F1. 충돌 3-way 병합 도구

**사용 흐름**

1. 충돌 파일(파일 상태의 *Conflict*)을 더블클릭하거나 우클릭 *병합 도구...* 를 고른다.
2. `MergeView` 탭이 열린다. 위에 **base / 내 것(ours) / 상대 것(theirs)**, 아래에 **결과**가 있다.
3. 충돌 덩어리마다 *내 것*, *상대 것*, *둘 다(내 것 먼저)*, *둘 다(상대 것 먼저)* 를 고르거나,
   결과 칸에서 직접 고친다. *다음 충돌* / *이전 충돌* 로 이동한다.
4. *저장하고 해결로 표시* 를 누르면 결과를 작업 트리에 쓰고 stage한다. 남은 충돌이 있으면 저장만 하고 알린다.

**구현**

* 내용: index stage 1/2/3 (`Revision.Stage`). base가 없으면(양쪽 추가) 빈 내용으로 둔다.
* 충돌 덩어리 계산: JGit `MergeAlgorithm(DiffAlgorithm.getAlgorithm(HISTOGRAM)).merge(RawTextComparator.DEFAULT, base, ours, theirs)`
  → `MergeResult<RawText>`의 chunk 목록(`ConflictState`: NO_CONFLICT, FIRST_CONFLICTING_RANGE,
  BASE_CONFLICTING_RANGE, NEXT_CONFLICTING_RANGE). base 범위는 3-way 화면의 base 칸 표시에 쓴다.
* 결과 칸: `TextArea` 대신 줄 단위 모델(`List<MergeLine>`, 각 줄에 출처 표시)을 두고 `ListView`로 보여 준다.
  충돌 덩어리 선택은 모델을 바꾸고, 직접 편집은 덩어리 단위 `TextArea` 편집 창으로 한다.
* 줄바꿈·인코딩: 원본 바이트를 줄 단위로 다루고(기존 hunk 처리와 같은 방식), 저장 시 그대로 쓴다.
* 해결 표시: 작업 트리에 쓰고 `stage(path)`. 기존 merge/rebase/cherry-pick 진행 흐름에 그대로 이어진다.

**`.bob` 위젯 단위 병합 (EGit에 없는 기능)**

텍스트 병합은 XML 줄 단위라 위젯 순서·들여쓰기 차이로 충돌이 잦다. `.bob`은 위젯 단위로 병합한다.

1. base/ours/theirs를 `BobDiff`와 같은 방식으로 파싱 (위젯 키 = 부모·종류·이름·순번).
2. 위젯·속성마다 3-way 판정:
   한쪽만 바뀜 → 자동 반영, 양쪽이 같게 바뀜 → 반영, 양쪽이 다르게 바뀜 → **위젯 속성 충돌**,
   한쪽 삭제 + 다른 쪽 수정 → 충돌.
3. 화면: 충돌 목록 (`textupdate 'temp'  pv_name: 내 것 SR:T1 / 상대 것 SR:T2`)에서 항목별로 선택.
4. 결과 XML 생성: **ours 문서를 기준으로** 선택된 값만 DOM에서 바꿔 쓴다.
   theirs에만 있는 위젯은 theirs의 위치(앞 위젯 기준)에 삽입한다.
   기존 서식을 최대한 유지하기 위해 ours DOM을 수정해 직렬화한다.
5. 저장 전 `ModelReader`로 읽혀지는지 검사한다(B3 재사용). 실패하면 텍스트 병합 도구로 넘어간다.

**규모**: 텍스트 병합 M, `.bob` 위젯 병합 L
**테스트**: `MergeModelTest`(덩어리 계산, 선택, CRLF·끝 줄바꿈), `BobMergeTest`(자동 반영, 속성 충돌, 삭제/수정 충돌, 위젯 추가 위치), UI 실행기(충돌 → 도구 → 해결 → 커밋)

### F2. 임의 버전 비교·복원

**사용 흐름**

| 위치 | 메뉴 |
|---|---|
| 파일 상태·커밋 파일 목록·File Browser(F3) | *비교 ▸ HEAD / Index / 브랜치·태그·커밋...*, *되돌리기 ▸ HEAD / Index / 브랜치·태그·커밋...* |
| 파일 이력 | *이 버전과 작업 트리 비교*, *두 버전 비교*(두 줄 선택), *이 버전으로 되돌리기* |
| 이력 | *작업 트리와 비교*, *두 커밋 비교*(두 줄 선택) |
| 사이드바 브랜치·태그 | *현재 브랜치와 비교* |

* 비교 결과는 `CompareView` 탭: 바뀐 파일 목록 + diff(unified/좌우/위젯 단위, 기존 `DiffPane` 재사용).
* **되돌리기**는 파일 내용을 그 버전으로 바꾼다. 확인 창에서 *작업 트리만* / *작업 트리와 index* 를 고른다.
  저장하지 않은 편집기가 그 파일을 열고 있으면 경고한다(A4 재사용).
* *이 버전을 열기*: 그 커밋의 파일을 읽기 전용 임시 위치에 꺼내 Display Runtime으로 연다.
  상대 경로 참조가 깨지지 않도록 **그 파일의 폴더 전체**를 같은 구조로 꺼낸다(제안서 B2와 같은 기능).

**구현**

* `GitRepository.compare(a, b)`, `getDiff(a, b, path)` (2.1).
* 되돌리기: `git.checkout().setStartPoint(commit).addPath(path)` 는 index와 작업 트리를 모두 바꾼다.
  작업 트리만 바꿀 때는 `getContent(revision, path)`를 파일에 직접 쓴다. 그 버전에 파일이 없으면 삭제를 확인한다.
* 버전 열기: `TreeWalk`로 폴더 아래 파일을 임시 폴더로 꺼내고, 탭을 닫으면 지운다.

**규모**: M
**테스트**: 리비전 조합별 비교(커밋·index·작업 트리·태그), 이름 변경, 되돌리기 두 가지 방식, 없는 파일 되돌리기, 버전 열기 상대 경로

### F3. File Browser Git 상태 표시

**사용 흐름**

File Browser의 파일·폴더 아이콘에 Git 상태 표시를 겹쳐 그린다(EGit decorator와 같은 방식).

| 상태 | 표시 |
|---|---|
| 수정됨 / stage됨 | 아이콘 오른쪽 아래 `>` 표시, 이름 앞에 `>` |
| 추가됨 / untracked | `+` / `?` |
| 충돌 | 빨간 `!` |
| ignore됨 | 회색 이름 |
| 폴더 | 안에 바뀐 파일이 있으면 `>` |
| 저장소 최상위 폴더 | 이름 뒤 `[master ↑1]` (브랜치, ahead/behind) |

**phoebus File Browser 변경 (upstream 제안 형태)**

```java
// core-ui: org.phoebus.ui.spi.FileDecorator
public interface FileDecorator
{
    /** @return Decoration for the file, or null; called on UI thread, must be fast (cached) */
    FileDecoration getDecoration(File file);

    /** Listener to request a refresh of decorations */
    void addListener(Runnable on_change);
}
public record FileDecoration(Image overlay, String prefix, String suffix, String style) {}
```

* `FileTreeCell.updateItem()`에서 `ServiceLoader`로 찾은 decorator들을 물어 아이콘을 겹치고 이름을 꾸민다.
* decorator가 변경을 알리면 File Browser가 보이는 셀만 다시 그린다.
* 변경 규모: `FileTreeCell`(약 30줄), 새 SPI 2개 파일. Git 앱 쪽 의존성 없음.

**Git 앱 쪽 구현 (`GitFileDecorator`)**

* 저장소마다 상태 캐시: 경로 → 상태. `getDecoration()`은 캐시만 읽는다(UI 스레드에서 Git 호출 없음).
* 처음 보는 폴더는 위로 올라가며 `.git`을 찾아 저장소를 등록하고, 백그라운드에서 상태를 읽은 뒤 변경을 알린다.
* 이미 있는 `RepositoryWatcher`로 변경을 감지해 캐시를 갱신한다. 열린 Git 탭이 있으면 그 탭의 상태를 그대로 쓴다.
* 설정 `file_browser_decorations=true`, 저장소당 감시 폴더 한도는 기존 값 사용.

**규모**: File Browser 변경 S, Git decorator M
**테스트**: 캐시·폴더 집계 단위 테스트, File Browser를 띄워 아이콘 표시 확인(UI 실행기)
**Windows 참고**: 경로 대소문자, 파일 감시 차이(추후)

### F4. Display Editor 연계

phoebus에는 일반 텍스트 편집기가 없으므로 EGit의 Quick Diff(편집기 여백 표시)는
**Display Editor용으로 다시 정의**한다.

**사용 흐름**

* 편집기 위젯 우클릭 메뉴(또는 빈 곳 우클릭):
  * *Git: HEAD 대비 바뀐 위젯 선택* — 바뀐·추가된 위젯을 선택 상태로 표시하고, 상태 표시줄에 개수를 보여 준다.
  * *Git: 이 위젯의 이력* — 이 위젯이 바뀐 커밋 목록(위젯 단위 blame)과 각 커밋에서의 속성 변경.
  * *Git: 화면 이력 / 비교* — 기존 파일 이력, F2 비교로 연결.
* Git 앱 History의 위젯 단위 diff에서 위젯 줄을 더블클릭하면, 그 화면이 편집기에 열려 있을 때 해당 위젯을 선택한다.

**phoebus Display Editor 변경 (upstream 제안 형태)**

Display Runtime이 PV에 대해 이미 하는 방식(`ContextMenuSupport`: `SelectionService` + `ContextMenuHelper`)을 편집기에도 적용한다.

1. 편집기 우클릭 메뉴를 열 때 선택된 위젯 목록과 화면 파일을 `SelectionService`에 넣고,
   `ContextMenuHelper.addSupportedEntries()`로 지원하는 항목을 붙인다.
   선택 타입: `Widget` (display model 클래스).
2. `DisplayEditorInstance`에 `public DisplayEditor getEditor()` 접근자 추가
   (위젯 선택용, `WidgetSelectionHandler.setSelection()`).

변경 규모: 편집기 우클릭 메뉴 생성부 약 20줄, 접근자 1개.

**위젯 단위 blame (`WidgetHistory`)**

* 파일 이력(`getFileHistory`, rename 추적)을 따라 이웃한 두 버전을 `BobDiff`로 비교하고,
  위젯 키가 바뀐 커밋을 모은다. 결과: 위젯 → [커밋, 바뀐 속성 목록].
* 비용: 이력 N개에 대해 파싱 N번. 처음 계산 후 커밋 id로 캐시한다. 기본 최근 200개까지.

**규모**: 편집기 변경 S, Git 쪽 M
**테스트**: `WidgetHistoryTest`(추가·수정·삭제·이름 변경 전후), UI 실행기(편집기에서 메뉴 → 선택 확인)

### F5. Interactive rebase

**사용 흐름**

1. 이력 우클릭 *여기서부터 interactive rebase...* (선택한 커밋 다음부터 현재 브랜치 끝까지).
2. `RebaseTodoView`: 커밋 목록에서 동작을 고른다 — **pick, reword, edit, squash, fixup, drop**.
   위/아래 버튼 또는 드래그로 순서를 바꾼다. squash·fixup은 앞 커밋과 합친다.
3. *시작* 하면 진행한다. reword는 메시지 창, squash는 합친 메시지 편집 창을 띄운다.
   edit에서 멈추면 파일 상태 화면에서 수정·amend 후 *Rebase 계속*. 충돌은 기존 rebase 흐름(F1 도구 포함).
4. 이미 push한 커밋이 포함되면 시작 전에 경고한다(강제 push 필요).

**구현**

* JGit `git.rebase().setUpstream(base).runInteractively(InteractiveHandler)`.
  `prepareSteps(List<RebaseTodoLine>)`에서 사용자가 정한 순서·동작을 반영한다.
  drop은 해당 줄을 목록에서 뺀다(JGit Action에 DROP 없음).
  `modifyCommitMessage(String)`에서 reword·squash 메시지 창을 UI 스레드에 띄우고 기다린다(인증 창과 같은 방식).
* 진행·중단은 기존 `continueRebase/skipRebase/abortRebase` 재사용.

**규모**: M
**테스트**: 순서 변경, squash 메시지 합치기, fixup, drop, reword, edit 멈춤·계속, 충돌 중단, push된 커밋 경고

### F6. 줄 단위 stage / unstage / 버리기

**사용 흐름**

diff에서 `+`/`-` 줄을 여러 개 선택(Shift·Ctrl)하고 우클릭 *선택한 줄 Stage* / *Unstage* / *버리기*.

**구현**

기존 hunk 처리(raw diff → `HunkHeader` → 줄 바이트 적용)를 확장한다.

* 선택한 줄로 **부분 hunk**를 만든다. git add -p의 편집 규칙과 같다.
  * stage: 선택 안 한 `-` 줄은 context(` `)로 바꾸고, 선택 안 한 `+` 줄은 뺀다. hunk 머리의 줄 수를 다시 계산한다.
  * unstage(역방향): 선택 안 한 `+` 줄을 context로, 선택 안 한 `-` 줄을 뺀다.
* 만든 부분 hunk를 기존 `applyHunk`로 적용한다. context 일치 검사도 그대로 유지한다.
* 화면 줄 → 원본 hunk 줄 대응: `DiffPane`이 hunk 번호와 hunk 안 줄 번호를 함께 기억한다.

**규모**: M
**테스트**: 부분 hunk 생성 규칙(추가만, 삭제만, 교체 일부), CRLF, 끝 줄바꿈 없음, 파일이 바뀌었을 때 거부

### F7. Patch 만들기·적용

* 만들기: 이력에서 커밋 하나 이상 선택 → *Patch 만들기...* → 저장 위치 선택.
  커밋마다 `From <id>`, 작성자, 날짜, 제목을 붙인 mbox 형식(git format-patch 호환)으로 쓴다.
  커밋하지 않은 변경은 파일 상태에서 *Patch 만들기* (unified diff).
* 적용: 툴바 *Patch 적용...* → 파일 선택 → 미리보기(바뀔 파일 목록) → JGit `ApplyCommand`로 작업 트리에 적용.
  적용에 실패한 파일은 목록으로 보여 준다.
* **규모**: S
* **테스트**: 만든 patch를 다른 clone에 적용, 이름 변경·추가·삭제 포함, 실패 보고

### F8. Submodule

* 사이드바 **SUBMODULES** 영역: 경로, 기록된 커밋, 실제 커밋, 상태(초기화 안 됨, 커밋 다름, 수정됨).
* 우클릭: *초기화·업데이트*, *Git 탭에서 열기*(submodule 저장소를 새 Git 탭으로).
* Clone 창에 *submodule 포함* 옵션.
* 파일 상태: submodule 경로는 `Submodule` 종류로 따로 표시하고, stage하면 기록된 커밋을 갱신한다.
* JGit: `SubmoduleWalk`, `SubmoduleInitCommand`, `SubmoduleUpdateCommand`, `CloneCommand.setCloneSubmodules(true)`.
  인증·SSH 설정은 기존 `configure()`를 submodule 명령에도 적용한다.
* **규모**: M

### F9. GPG 서명 커밋 (의존성 추가 필요)

* JGit 6.9의 서명은 `org.eclipse.jgit.gpg.bc`(BouncyCastle) 모듈이 필요하다.
  **현재 phoebus 배포본과 오프라인 저장소에 없다.** 추가하면 `dependencies/phoebus-target`, 오프라인 설치 번들,
  라이선스 검토가 필요하다.
* 기능: 커밋 시 `commit.gpgsign`, `user.signingkey` 설정을 따르고, 커밋 영역에 *서명* 옵션.
  이력에 서명 여부·검증 결과 표시(JGit `VerifySignatureCommand`).
* SSH 키 서명은 JGit 7 이상에서 지원하므로 JGit 업그레이드와 함께 검토한다.
* **규모**: S (의존성 결정 후)

### F10. ssh-agent (의존성 추가 필요)

* `org.eclipse.jgit.ssh.apache.agent`(JNA 사용) 모듈 필요. **현재 없음.** F9와 같은 절차가 필요하다.
* 기능: `SshdSessionFactoryBuilder`에 agent connector 설정. 설정 `ssh_agent=auto|on|off`.
* **규모**: S (의존성 결정 후)
* **Windows 참고**: Pageant/OpenSSH agent는 Windows 작업 때 함께 검증

### F11. 원격 고급 설정

* 원격 저장소 편집 창을 확장한다: fetch URL / push URL 분리, fetch refspec 목록, push refspec 목록,
  *fetch 시 지워진 브랜치 정리(prune)*, *태그 가져오기(auto/all/none)*.
* JGit `RemoteConfig`로 읽고 쓴다. 기존 fetch의 prune 고정 동작은 이 설정을 따르게 바꾼다.
* **규모**: S

## 4. phoebus 다른 모듈 변경 요약

| 모듈 | 변경 | 필요 기능 | upstream PR |
|---|---|---|---|
| core-ui | `FileDecorator` SPI, `FileDecoration` | F3 | 권장 (일반 기능) |
| app-filebrowser | `FileTreeCell`이 decorator 사용 | F3 | 권장 |
| app-display-editor | 우클릭 메뉴에 `SelectionService`/`ContextMenuHelper` 연결, `getEditor()` 접근자 | F4 | 권장 (Runtime과 같은 방식) |
| dependencies/phoebus-target | jgit.gpg.bc + BouncyCastle, jgit.ssh.apache.agent + JNA | F9, F10 | 해당 없음 (배포 결정) |

## 5. 단계별 계획

| 단계 | 내용 | 규모 | 비고 |
|---|---|---|---|
| 0 | 공통 기반: 리비전 모델, `GitView` 분리, `CompareView` | M | 이후 단계의 전제 |
| 1 | F2 비교·복원, F1 텍스트 병합 도구 | M + M | EGit 사용자가 가장 먼저 찾는 기능 |
| 2 | F3 File Browser 표시, F4 편집기 연계 | (S+M) + (S+M) | phoebus 모듈 변경 포함 |
| 3 | F1 `.bob` 위젯 병합, F6 줄 단위 stage, F5 interactive rebase | L + M + M | |
| 4 | F7 patch, F8 submodule, F11 원격 설정 | S + M + S | |
| 5 | F9 GPG, F10 ssh-agent | S + S | 의존성 추가 결정 후 |

규모: S ≈ 1~2일, M ≈ 3~5일, L ≈ 1~2주 (구현 + 단위 테스트 + UI 실행기 검증 기준의 대략적인 상대 크기)

## 6. 검증 계획

* 기능마다 단위 테스트(JGit 임시 저장소)와 UI 실행기 시나리오를 추가하고, 기존 E2E(73), 연계(21), 검사·로그북(12)을 회귀로 함께 돌린다.
* F3·F4는 실제 File Browser·Display Editor 탭을 띄워 확인한다.
* 병합 도구는 실제 `.bob` 충돌 사례(위젯 순서 변경, 같은 위젯 다른 속성, 한쪽 삭제)로 검증한다.
* 단계 1이 끝나면 화면 개발자와 시범 운영해 실사용 피드백을 받는다.

## 7. 결정이 필요한 사항

1. **phoebus 모듈 변경 승인** (F3, F4): File Browser·Display Editor를 고쳐도 되는지, upstream PR도 보낼지.
2. **의존성 추가 여부** (F9, F10): BouncyCastle·JNA를 배포본과 오프라인 번들에 넣을지.
3. **`.bob` 병합 정책** (F1): 위젯 병합 결과를 자동 반영할 범위(한쪽만 바뀐 속성 자동 반영을 기본으로 할지),
   서식(들여쓰기·속성 순서)을 ours 기준으로 유지하는 방식에 동의하는지.
4. **진행 순서**: 위 단계 순서대로 할지, 특정 기능을 앞당길지.

## 8. 구현 결과 (2026-10-29)

| # | 상태 | 구현 내용과 설계와 다른 점 |
|---|---|---|
| 0 | 완료 | `Revision`, `GitRepository`의 `resolve/compare/getDiff/getContent/getVersions/restore/export`. `GitView` 전체 분리 대신 비교·되돌리기·병합 도구 열기를 `GitActions`로 분리하고, 새 기능은 별도 클래스로 둠 (기존 검증된 흐름 유지) |
| F1 | 완료 | `MergeModel`/`MergeView` 텍스트 3-way 병합. `.bob`은 `BobMerge`로 위젯·속성 단위 병합(위젯 단위 탭이 먼저, 텍스트 탭도 제공). 상대 쪽의 위젯 순서 변경은 반영하지 않음(ours 순서 유지). 위젯을 담은 속성(탭 구성)이 양쪽에서 바뀌면 텍스트 병합만 제공 |
| F2 | 완료 | `CompareView`, 파일 상태·이력·커밋 파일·파일 이력·사이드바 메뉴, 되돌리기(작업 트리만 / index까지), 이 버전 열기(폴더째 읽기 전용 임시 폴더, 종료 시 삭제). rename 감지 기준을 git 명령과 같은 50%로 맞춤 |
| F3 | 완료 | core-ui `FileDecorator`/`FileDecoration` SPI, File Browser `FileTreeCell`·컨트롤러 변경, `GitFileDecorator`. 표시는 이름 앞 글자와 색(겹침 그림 SPI는 있으나 Git은 사용 안 함). 상태 읽기가 저장소를 바꾸지 않음을 확인하고 변경마다 다시 읽음 |
| F4 | 완료 | Display Editor 우클릭 메뉴에 `SelectionService`/`ContextMenuHelper` 연결, `getDisplayEditor()` 접근자. HEAD 대비 바뀐 위젯 선택(저장 안 한 변경 포함, HEAD는 ModelReader→ModelWriter로 정규화해 비교), 위젯 이력, 화면 이력, HEAD와 비교, 위젯 diff 더블클릭으로 편집기 위젯 선택 |
| F5 | 완료 | `InteractiveRebaseDialog`, pick/reword/edit/squash/fixup/drop, 순서 변경, push된 커밋 경고, 첫 단계 squash 거부, 메시지 편집 창. merge 커밋이 있으면 시작하지 않음 |
| F6 | 완료 | diff 여러 줄 선택 → stage/unstage/버리기. git add -p 규칙에서 한 단계 나아가, 바뀐 덩어리 안에서 고른 추가 줄을 고른 삭제 줄 자리에 넣어 줄 순서 유지 |
| F7 | 완료 | 커밋 → format-patch(mbox, `git am` 호환 확인), 커밋 안 한 변경 → unified diff, Patch 적용(미리보기, JGit `ApplyCommand`) |
| F8 | 완료 | 사이드바 SUBMODULES, 초기화·업데이트, Git 탭에서 열기, Clone 시 포함. `git submodule deinit` 뒤 남은 `.git/modules` 재연결 (JGit 그대로는 실패) |
| F9 | 보류 | `org.eclipse.jgit.gpg.bc`가 오프라인 저장소에 없음. 의존성 결정 후 |
| F10 | 보류 | `org.eclipse.jgit.ssh.apache.agent`가 오프라인 저장소에 없음. 의존성 결정 후 |
| F11 | 완료 | 원격 저장소 설정 창(fetch/push URL, refspec, prune, 태그). fetch가 원격별 prune·태그 설정을 따름. prune 설정이 없으면 이전처럼 정리 |

검증: 단위 테스트 102개(신규 38개), UI 실행기 단계별 시나리오 76개(F1·F2 31, F3·F4 15, 단계 3 15, 단계 4 15),
기존 E2E 73·연계 21·검사/로그북 12 회귀.
