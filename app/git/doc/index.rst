Git
===

Git 앱은 phoebus 안에서 로컬 Git 저장소를 다룹니다. 디스플레이(.bob), 설정 파일처럼
Git으로 관리하는 파일의 변경 확인, 커밋, 브랜치 작업, 원격 저장소와의 동기화를
phoebus를 벗어나지 않고 할 수 있습니다. 화면 구성은 Sourcetree와 비슷합니다.
Git 처리는 JGit으로 하므로 시스템에 ``git`` 명령이 없어도 됩니다.

메뉴 *Utility* → *Git* 또는 툴바의 Git 버튼으로 엽니다.
툴바의 *Repository* 칸에 경로를 입력하거나 *Browse...* 로 저장소 폴더를 고릅니다.
저장소 안의 하위 폴더를 골라도 상위의 ``.git`` 을 찾아 엽니다.

화면 구성
---------

툴바
    *Fetch*, *Pull*, *Push* (받을/보낼 커밋 수 표시), *Branch*, *Merge*, *Tag*, *Stash*, *Refresh*.
    작업이 진행 중이면 이름과 진행 표시가 나옵니다. 작업은 하나씩 차례로 실행됩니다.

사이드바
    * **작업 공간** : *파일 상태* 와 *이력* 화면 전환
    * **브랜치** : 로컬 브랜치. 현재 브랜치는 굵게, ↑ 보낼 커밋 수, ↓ 받을 커밋 수.
      더블클릭하면 checkout
    * **태그**, **원격** (원격 저장소별 브랜치, 마우스를 올리면 URL), **Stash**

    각 항목의 우클릭 메뉴에서 checkout, 새 브랜치, merge, 삭제, 태그 push,
    원격 저장소 추가/URL 변경/제거, stash 적용/꺼내기/삭제를 할 수 있습니다.

파일 상태
---------

* **Staged 파일** : 다음 커밋에 들어갈 변경 (HEAD 대비 index)
* **Unstaged 파일** : 아직 stage하지 않은 변경, 충돌, untracked 파일 (index 대비 작업 트리)

체크박스를 누르면 바로 stage 또는 unstage됩니다. *모두 Stage*, *모두 Unstage* 버튼과
우클릭 메뉴(여러 파일 선택 가능)도 있습니다. 우클릭의 *변경 버리기* 는 tracked 파일을
index 상태로 되돌리고 untracked 파일은 삭제합니다. 되돌릴 수 없으므로 확인 후 실행됩니다.

파일을 고르면 오른쪽에 diff가 나옵니다. *좌우 비교* 를 켜면 이전/이후를 나란히 보여 줍니다.

아래쪽에 커밋 메시지를 쓰고 *커밋* 을 누르면 staged 변경만 커밋됩니다.
*마지막 커밋 수정(amend)* 을 켜면 직전 커밋을 대신하며, 직전 메시지가 미리 채워집니다.

이력
----

모든 브랜치(또는 *모든 브랜치* 를 끄면 현재 브랜치)의 커밋을 그래프로 보여 줍니다.
브랜치는 초록, 원격 브랜치는 파랑, 태그는 노랑 라벨입니다.
기본으로 최근 100개 커밋을 읽고, *더 불러오기* 로 100개씩 더 읽습니다.
커밋하지 않은 변경이 있으면 맨 위에 *커밋하지 않은 변경* 행이 나오고,
더블클릭하면 파일 상태 화면으로 갑니다.

커밋 우클릭 메뉴: 새 태그, 여기서 새 브랜치, cherry-pick, revert,
현재 브랜치를 여기로 reset (soft / mixed / hard), 커밋 ID 복사.

Merge와 충돌
------------

*Merge* 는 고른 브랜치를 현재 브랜치로 합칩니다. 충돌이 나면 파일 상태 화면으로 바뀌고
충돌 파일이 *Conflict* 로 표시되며, merge 메시지가 미리 채워집니다.
작업 트리에서 충돌을 해결하고 파일을 stage한 뒤 커밋하거나, *Merge 중단* 으로 취소합니다.
cherry-pick, revert에서 난 충돌도 같은 방식으로 처리합니다.

원격 저장소 인증
----------------

HTTPS
    사용자 이름과 비밀번호(또는 액세스 토큰)를 묻습니다.
    *보안 저장소에 저장* 을 고르면 호스트별로 phoebus 보안 저장소에 저장되고,
    인증에 실패하면 다시 묻습니다.

SSH
    ``ssh_directory`` (기본 ``~/.ssh``)의 기본 키와 ``config`` 를 사용합니다.
    키 passphrase와 처음 접속하는 서버의 호스트 키 확인은 대화상자로 묻습니다.
    ssh-agent는 지원하지 않습니다.

설정
----

``git_preferences.properties`` 의 항목을 phoebus 설정 파일에서
``org.phoebus.applications.git/항목=값`` 으로 바꿀 수 있습니다.

.. code-block:: properties

    # 시작할 때 열 저장소. 비우면 선택하지 않은 상태로 시작
    org.phoebus.applications.git/default_repository=

    # 이력에서 한 번에 읽는 커밋 수
    org.phoebus.applications.git/log_count=100

    # SSH 키, known_hosts, config 가 있는 폴더
    org.phoebus.applications.git/ssh_directory=$(user.home)/.ssh

    # 사용할 SSH 개인키. 비우면 ssh_directory의 기본 키 사용
    org.phoebus.applications.git/ssh_private_key=

    # fetch/pull/push 타임아웃(초)
    org.phoebus.applications.git/remote_timeout=30

화면 문구는 JVM 언어 설정을 따릅니다. 한국어 환경에서는 한국어로 나오며,
영어로 보려면 ``-Duser.language=en`` 으로 실행합니다.

제한 사항
---------

* 줄/hunk 단위 stage, rebase, ssh-agent는 지원하지 않습니다.
* *Merge 중단* 과 hard reset은 커밋하지 않은 변경을 모두 버립니다.
* 태그 push와 원격 태그 삭제는 ``origin`` (없으면 첫 번째 원격 저장소)을 대상으로 합니다.
