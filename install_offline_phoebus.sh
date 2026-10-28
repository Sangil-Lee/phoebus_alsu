#!/usr/bin/env bash
#
#  install_offline_phoebus.sh : Offline installer for Phoebus and its alarm
#                               services
#
#  Supported OS : Debian 11/12/13 and Ubuntu derivatives
#                 Rocky / AlmaLinux / RHEL / CentOS Stream 8, 9, 10
#
#  The installation is done in two steps.
#
#  1. on a machine which has internet access
#
#         ./install_offline_phoebus.sh bundle --bundle-dir=phoebus-bundle
#
#     builds Phoebus if needed and collects everything into one directory :
#
#         phoebus-bundle/manifest.txt   distribution, versions, commit
#         phoebus-bundle/dist/          the built product and services
#         phoebus-bundle/tarballs/      JDK, Kafka, Elasticsearch
#         phoebus-bundle/pkgs/          OS packages and their dependencies
#
#  2. on the machine without internet access
#
#         ./install_offline_phoebus.sh --bundle=phoebus-bundle.tar.gz -y
#
#  The bundle carries what Maven produced, not the source and not the Maven
#  repository : the offline machine installs, it never builds. That makes the
#  bundle about 1.6 GB instead of 2.3 GB and the installation minutes instead
#  of an hour. Use install_phoebus.sh on a machine which has to rebuild.
#
#  The bundled OS packages only fit a machine running the same distribution,
#  major version and architecture. The manifest records all three and the
#  installation stops on a mismatch, unless --force-os is given.
#
#  version : 0.1.0
#
set -euo pipefail

declare -g SC_SCRIPT SC_NAME TOP
SC_SCRIPT="$(realpath "${BASH_SOURCE[0]:-$0}")"
SC_NAME="${SC_SCRIPT##*/}"
TOP="${SC_SCRIPT%/*}"

## ----------------------------------------------------------------------------
## Defaults, all of them can be changed through the command line options
## ----------------------------------------------------------------------------
## 'kafka' and 'elastic' come before 'install' : the topics of the alarm
## configuration are created on a running broker.
## No 'build' : the offline machine installs what the bundle carries. The
## stage still exists and is used by 'bundle' on the machine which has the
## network, it is simply not part of a default run.
ALL_STAGES=(pkgs java kafka elastic install topics service verify)
EXTRA_STAGES=(bundle src build paths exist status uninstall)
NOSUDO_STAGES=(paths exist status)

## Bundle : written by the 'bundle' stage, consumed by every other one
BUNDLE=""                        # --bundle=DIR|TARBALL
BUNDLE_DIR="phoebus-bundle"      # --bundle-dir=DIR , where 'bundle' writes
BUNDLE_WORK=""                   # the opened bundle, set by open_bundle()
BUNDLE_TMP=""                    # temporary extraction directory to clean up
BUNDLE_VERSION="1"
WITH_PKGS="true"                 # --no-pkgs   : leave the OS packages out
WITH_JDK="true"                  # --with-jdk=no : leave the Temurin JDK out
FORCE_OS="false"                 # --force-os  : ignore a distribution mismatch
BUNDLE_PACK="true"               # --no-pack   : do not create the tar.gz

## Where the Phoebus source comes from, used by 'bundle' and 'build' on the
## machine which has the network. The offline machine never needs it.
SRC_REPO_FORK="https://github.com/Sangil-Lee/phoebus_alsu"
SRC_REPO_UPSTREAM="https://github.com/ControlSystemStudio/phoebus"
SRC_REPO=""                      # --src-repo=fork | upstream | URL
SRC_REF="master"                 # --src-ref=BRANCH|TAG|COMMIT
SRC_DIR=""                       # --src-dir=PATH , where the source lives

JDK_MAJOR="17"                   # Phoebus needs JDK 17 or later
JAVA_ENV_PREFIX="/opt/java-env"  # where a downloaded JDK / Maven go
MAVEN_VER="3.9.9"

PHOEBUS_USER="phoebus"           # account running the alarm services
PHOEBUS_PREFIX="/opt/phoebus"            # the desktop product
SERVICES_PREFIX="/opt/phoebus-services"  # the alarm services

ALARM_CONFIG="Accelerator"       # alarm configuration, also the Kafka topic name
KAFKA_HOST="localhost"
KAFKA_PORT="9092"
ES_HOST="localhost"
ES_PORT="9200"
ALARM_LOGGER_PORT="9000"         # server.port of the alarm logger REST service
SAVE_RESTORE_PORT="9100"         # server.port of the save-and-restore service
SAVE_RESTORE_PATH="/save-restore"  # server.servlet.contextPath of that service
ALARM_CONFIG_LOGGER_PORT="8081"  # spring boot port of the configuration logger
ALARM_CONFIG_REPO="/var/lib/phoebus/alarm-config-repo"   # local git repo of the config logger

KAFKA_VER="3.9.1"
KAFKA_SCALA="2.13"
KAFKA_PREFIX="/opt/kafka"
KAFKA_DATA="/var/lib/kafka"
## Kafka's own log4j output must NOT live inside log.dirs : the LogManager reads
## every directory below it as a topic partition and refuses to start otherwise
KAFKA_LOGS="/var/log/kafka"
KAFKA_USER="kafka"

ES_VER="8.11.4"
ES_PREFIX="/opt/elasticsearch"
ES_DATA="/var/lib/elasticsearch"
ES_LOGS="/var/log/elasticsearch"
ES_USER="elasticsearch"

EPICS_PV_DEFAULT="ca"            # org.phoebus.pv/default
EPICS_ADDR_LIST=""               # org.phoebus.pv.ca/addr_list, empty : auto
EPICS_AUTO_ADDR_LIST="true"

WITH_CONFIG_LOGGER="true"        # --no-config-logger disables it
WITH_SAVE_RESTORE="true"         # --no-save-restore disables it
WITH_DESKTOP="true"              # --no-desktop skips the JavaFX product
SKIP_TESTS="true"                # the build runs with -DskipTests by default
OPEN_FIREWALL="false"
ASSUME_YES="false"
DRY_RUN="false"
LOG_FILE="${HOME}/install_offline_phoebus.log"

## Maven downloads a lot for this project, some networks reset large transfers
MAVEN_NET_OPTS="-Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.httpconnectionManager.ttlSeconds=120"
MAVEN_USER_OPTS=""

## Filled by detect_os()
OS_ID=""; OS_LIKE=""; OS_VERSION_ID=""; OS_MAJOR=""; OS_FAMILY=""; OS_PRETTY=""
## Filled by stage_java()
JAVA_HOME_DETECTED=""; MAVEN_HOME_DETECTED=""
## Filled by parse_args()
REQUESTED_STAGES=()
declare -A OPT_SET=()

## The systemd units this script owns, in start order
ALARM_UNITS=(phoebus-alarm-server.service phoebus-alarm-logger.service phoebus-alarm-config-logger.service)
## save-and-restore only needs Elasticsearch, it is not part of the alarm chain
SR_UNITS=(phoebus-save-and-restore.service)
INFRA_UNITS=(kafka.service elasticsearch.service)

## ----------------------------------------------------------------------------
## Pretty printing and logging
## ----------------------------------------------------------------------------
if [[ -t 1 ]]; then
    C_RED=$'\033[1;31m'; C_GRN=$'\033[1;32m'; C_YEL=$'\033[1;33m'
    C_BLU=$'\033[1;34m'; C_OFF=$'\033[0m'
else
    C_RED=""; C_GRN=""; C_YEL=""; C_BLU=""; C_OFF=""
fi

function log   { printf '%s\n' "$*" >> "${LOG_FILE}" 2>/dev/null || true; }
function info  { printf '%s>>>%s %s\n'   "${C_BLU}" "${C_OFF}" "$*"; log ">>> $*"; }
function ok    { printf '%s[ OK]%s %s\n' "${C_GRN}" "${C_OFF}" "$*"; log "[ OK] $*"; }
function warn  { printf '%s[ !!]%s %s\n' "${C_YEL}" "${C_OFF}" "$*" >&2; log "[ !!] $*"; }
function error { printf '%s[ERR]%s %s\n' "${C_RED}" "${C_OFF}" "$*" >&2; log "[ERR] $*"; }
function die   { error "$*"; [[ -s "${LOG_FILE}" ]] && error "see the log file ${LOG_FILE}"; exit 1; }

function banner
{
    printf '\n%s------------------------------------------------------------%s\n' "${C_BLU}" "${C_OFF}"
    printf '%s  %s%s\n' "${C_BLU}" "$*" "${C_OFF}"
    printf '%s------------------------------------------------------------%s\n\n' "${C_BLU}" "${C_OFF}"
    log ""; log "=== $* ==="
}

## try : run, log, return the status.  run : the same but abort on failure.
function try
{
    log "\$ $*"
    if [[ "${DRY_RUN}" == "true" ]]; then
        printf '%s[dry]%s %s\n' "${C_YEL}" "${C_OFF}" "$*"
        return 0
    fi
    "$@" 2>&1 | tee -a "${LOG_FILE}"
    return "${PIPESTATUS[0]}"
}
function run { try "$@" || die "command failed : $*"; }

function try_sh
{
    log "\$ $*"
    if [[ "${DRY_RUN}" == "true" ]]; then
        printf '%s[dry]%s %s\n' "${C_YEL}" "${C_OFF}" "$*"
        return 0
    fi
    bash -c "$*" 2>&1 | tee -a "${LOG_FILE}"
    return "${PIPESTATUS[0]}"
}
function run_sh { try_sh "$*" || die "command failed : $*"; }

## Write a file which needs root, from a here string
function write_root_file
{
    local dest="$1"; shift
    if [[ "${DRY_RUN}" == "true" ]]; then
        printf '%s[dry]%s would write %s\n' "${C_YEL}" "${C_OFF}" "${dest}"
        return 0
    fi
    printf '%s' "$*" | sudo tee "${dest}" >/dev/null
    log "--- ${dest} ---"; log "$*"
    ok "generated ${dest}"
}

function ask_yes
{
    local prompt="$1" answer
    [[ "${ASSUME_YES}" == "true" ]] && return 0
    read -r -p "${prompt} [y/N] " answer
    [[ "${answer}" =~ ^[Yy] ]]
}

function print_kv { printf '  %-26s %s\n' "$1" "$2"; }

function print_state
{
    local mark
    if [[ "$2" == "ok" ]]; then mark="${C_GRN}[ OK]${C_OFF}"; else mark="${C_YEL}[ --]${C_OFF}"; fi
    printf '  %b %-24s %s\n' "${mark}" "$1" "$3"
}

## ----------------------------------------------------------------------------
## Usage
## ----------------------------------------------------------------------------
function usage
{
    cat <<EOF

Usage : ${SC_NAME} [OPTIONS] [STAGE ...]

  Installs Phoebus and its alarm services on a machine without internet
  access, from a bundle built on a machine which has it.

  Step 1, where there is a network :

      ./${SC_NAME} bundle --bundle-dir=phoebus-bundle

  builds Phoebus if the artifacts are not there yet and collects what an
  offline machine needs : the product and the services Maven produced, the
  JDK, Kafka and Elasticsearch tarballs, and the OS packages.

  Step 2, on the machine without internet access :

      ./${SC_NAME} --bundle=phoebus-bundle.tar.gz -y

  The bundle carries build output, not sources and not the Maven repository :
  the offline machine installs, it never builds. Use install_phoebus.sh where
  a rebuild is needed.

STAGES (default : all of them, in this order)

  pkgs      Install the OS packages, from the bundle when one is given
  java      Install or detect JDK ${JDK_MAJOR}+, from the bundle when one is given
  kafka     Install Apache Kafka ${KAFKA_VER} in KRaft mode and its systemd unit
  elastic   Install Elasticsearch ${ES_VER}, single node, and its systemd unit
  install   Install the product, the alarm services and their settings
  topics    Create the Kafka topics of the alarm configuration
  service   Start and enable every systemd unit
  verify    Wait for Kafka, Elasticsearch and the alarm logger, print a summary

  These ones are never part of a default run :

  bundle    Collect everything an offline machine needs (needs the network)
  src       Clone the Phoebus source, used by 'bundle'
  build     Build Phoebus with Maven, used by 'bundle'
  paths     Print every path, port and setting in use            (no sudo)
  exist     Check what is installed and what is missing          (no sudo)
  status    Print the state of the five systemd units            (no sudo)
  uninstall Stop and remove the services, keep Kafka and Elasticsearch data

OPTIONS

  -y, --yes                  Do not ask anything, assume "yes"
  -n, --dry-run              Only print what would be done
      --log-file=FILE        Log file (default : ${LOG_FILE})

      --bundle=PATH          Install from this bundle, a directory or a tar.gz
      --bundle-dir=DIR       Where the 'bundle' stage writes  (default : ${BUNDLE_DIR})
      --no-pkgs              Leave the OS packages out of the bundle
      --with-jdk=no          Leave the Temurin JDK out of the bundle
      --no-pack              Leave the bundle unpacked, no tar.gz
      --force-os             Install a bundle built on another distribution,
                             its OS packages are then ignored

      --src-repo=WHICH       Where 'bundle' takes the source from :
                               fork      ${SRC_REPO_FORK}
                               upstream  ${SRC_REPO_UPSTREAM}
                               or any git URL
                             Default : the origin of the checkout this script
                             sits in, ${SRC_REPO_FORK} when there is none.
      --src-ref=REF          Branch, tag or commit to build (default : ${SRC_REF})
      --src-dir=PATH         Where the source lives, ./phoebus-src by default

      --config=NAME          Alarm configuration and Kafka topic (default : ${ALARM_CONFIG})
      --user=NAME            Account running the alarm services (default : ${PHOEBUS_USER})
      --prefix=PATH          Where the desktop product goes      (default : ${PHOEBUS_PREFIX})
      --services-prefix=PATH Where the services go               (default : ${SERVICES_PREFIX})
      --no-config-logger     Do not install the alarm configuration logger
      --no-save-restore      Do not install the save-and-restore service
      --save-restore-port=N  Its REST port (default : ${SAVE_RESTORE_PORT})
      --no-desktop           Do not install the JavaFX desktop product

      --kafka-version=VER    Apache Kafka version    (default : ${KAFKA_VER})
      --kafka-prefix=PATH    Kafka installation      (default : ${KAFKA_PREFIX})
      --kafka-host=HOST      Broker host advertised  (default : ${KAFKA_HOST})
      --es-version=VER       Elasticsearch version   (default : ${ES_VER})
      --es-prefix=PATH       Elasticsearch install   (default : ${ES_PREFIX})
      --es-host=HOST         Elasticsearch host      (default : ${ES_HOST})

      --pv-default=TYPE      org.phoebus.pv/default, ca or pva (default : ${EPICS_PV_DEFAULT})
      --addr-list=LIST       EPICS channel access address list
      --no-auto-addr-list    Set auto_addr_list to false

      --jdk-major=N          JDK major version to look for (default : ${JDK_MAJOR})
      --maven-opts=OPTS      Extra JVM safe options for Maven (-D... only)
      --with-tests           Run the unit tests during the build (long)
      --open-firewall        Open ${KAFKA_PORT}, ${ES_PORT} and ${ALARM_LOGGER_PORT} in firewalld or ufw

  -h, --help                 This message

INSTALLATION IN DETAIL

  Every stage is idempotent : running it again only refreshes what changed.
  The offline installation takes minutes, the bundle it consumes takes 30 to
  50 minutes to build on the machine which has the network.

  0. bundle    on the machine with internet access

     Fetches the source and builds Phoebus first when there is no build output
     yet. --src-repo picks the repository, 'fork' and 'upstream' being
     shortcuts for the two usual ones, --src-ref a branch, a tag or a commit :

         ./${SC_NAME} bundle --src-repo=upstream --src-dir=~/phoebus-upstream
         ./${SC_NAME} bundle --src-repo=fork --src-ref=v5.0.0

     A checkout this script already sits in is used as is and keeps its own
     origin. Then it collects :

       dist/       what Maven produced : the desktop product, the three alarm
                   services and save-and-restore. product-*.tar.gz for the desktop,
                   service-alarm-server-*-bin.tar.gz which carries the thin
                   jar together with the lib directory its manifest needs,
                   and the two Spring Boot jars of the loggers. The
                   settings.ini of ${PHOEBUS_PREFIX} travels along when it
                   exists, so a site configuration reaches the target.
       tarballs/   the Temurin JDK, Kafka ${KAFKA_VER} and Elasticsearch ${ES_VER}
       pkgs/       the OS packages and their dependencies. No JDK and no
                   Maven : the JDK is a tarball and nothing is built on the
                   target. --no-pkgs leaves them out when the target machine
                   has its own mirror.
       manifest.txt  distribution, major version, architecture, the
                   repository, the reference and the commit the artifacts
                   were built from, and every version

     An interrupted run continues, the parts which are already complete are
     kept. About 1.7 GB in total.

         ./${SC_NAME} bundle
         ./${SC_NAME} bundle --bundle-dir=/data/phoebus-2026-08 --no-pkgs

  Everything below runs on the machine without internet access, against
  --bundle. Without a bundle these stages behave like install_phoebus.sh and
  reach out to the network instead.

  1. pkgs      the OS packages, from the bundle

     'dnf install --disablerepo=*' on the bundled rpms, 'dpkg -i' on the
     debs. They only fit a machine of the same distribution, major version
     and architecture as the one which built the bundle, which the manifest
     records and this script checks. On a mismatch it stops, or ignores the
     packages with --force-os and leaves them to your mirror.

  2. java      a JDK ${JDK_MAJOR} or later, from the bundle

     An installed JDK is reused, otherwise the Temurin tarball of the bundle
     is unpacked into ${JAVA_ENV_PREFIX}/JDK. Maven is never needed here,
     nothing is compiled on this machine.

  3. kafka     Apache Kafka ${KAFKA_VER} in KRaft mode, no Zookeeper

     The tarball comes from the bundle and goes to ${KAFKA_PREFIX}, owned by
     the '${KAFKA_USER}' account. Broker and controller in one process,
     listening on ${KAFKA_HOST}:${KAFKA_PORT}. Topic data lives in
     ${KAFKA_DATA} and Kafka's own log4j output in ${KAFKA_LOGS} : the two
     must stay apart, the LogManager reads every directory inside
     ${KAFKA_DATA} as a topic partition and refuses to start otherwise. The
     storage is formatted once, a second run keeps the existing data.

  4. elastic   Elasticsearch ${ES_VER}, a single node

     From the bundle into ${ES_PREFIX}, owned by '${ES_USER}', data in
     ${ES_DATA}, logs in ${ES_LOGS}. It binds to ${ES_HOST} only, which keeps
     the node in development mode so the bootstrap checks are skipped, and
     security is off : the alarm logger talks to it without credentials.
     Elasticsearch brings its own JDK.

  5. install   the services, their settings and the product

     Each distribution is extracted straight into its destination, never
     through a temporary directory : 'cp -a tmp/. dest/' would stamp the mode
     and the owner of that temporary directory onto dest.

       alarm-server/         the jar and its lib directory, alarm_settings.ini
                             passed with -settings
       alarm-logger/         the Spring Boot jar, alarm_logger.properties
                             passed with -properties, REST on ${ALARM_LOGGER_PORT}
       alarm-config-logger/  the Spring Boot jar and its properties, the
                             configuration history is committed into
                             ${ALARM_CONFIG_REPO}/<config>, one git repository
                             per alarm configuration, nothing is pushed
                             anywhere until remote.location is set

     The desktop product is unpacked into ${PHOEBUS_PREFIX} with a launcher
     in /usr/local/bin/phoebus and a desktop entry.
     ${PHOEBUS_PREFIX}/settings.ini is the reference settings file, and every
     value which is not given on the command line is read back from it, so a
     partial run never resets what the site configured.

     Then the five systemd units. The alarm units are ordered server ->
     logger -> config-logger with After=, so the chain holds when the machine
     boots, and all of them declare SuccessExitStatus=143 because a JVM
     stopped with SIGTERM exits with that code.

  6. topics    the Kafka topics of the alarm configuration

     Starts the broker and creates ${ALARM_CONFIG} compacted, plus
     ${ALARM_CONFIG}Command and ${ALARM_CONFIG}Talk with a short retention,
     with the settings of app/alarm/examples/create_alarm_topics.sh. Existing
     topics are left untouched.

  7. service   enable and start everything, in order

     kafka and elasticsearch first, and this stage then waits for their ports
     before it starts what depends on them : both are Type=simple, so systemd
     calls them started as soon as the process exists, long before they accept
     a connection, and save-and-restore in particular connects to
     Elasticsearch while it boots.

  8. verify    does the stack answer

     Waits for ${KAFKA_PORT}, ${ES_PORT} and ${ALARM_LOGGER_PORT}, prints the
     cluster health and the state of the five units.

  build        only on the machine which builds the bundle

     Not part of a default run here. 'bundle' calls it when there is no build
     output yet, and --maven-opts and --with-tests apply to it.

AFTER THE INSTALLATION

  An empty alarm configuration is of no use, so import one. On an offline
  machine that XML has to travel with the bundle, it is not part of it. The
  format follows app/alarm/examples/alarm_configuration.xsd :

      <config name="${ALARM_CONFIG}">
        <component name="Test">
          <pv name="TEST:TEMP">
            <description>Test analog temperature</description>
            <enabled>true</enabled>
            <latching>false</latching>
          </pv>
        </component>
      </config>

  The import writes into the Kafka topic, so stop the server first :

      sudo systemctl stop phoebus-alarm-server
      cd ${SERVICES_PREFIX}/alarm-server
      sudo -u ${PHOEBUS_USER} java -jar alarm-server.jar \\
           -settings alarm_settings.ini -config ${ALARM_CONFIG} \\
           -import my_config.xml -noshell
      sudo systemctl start phoebus-alarm-server

  Checking the whole chain with a soft IOC, the way this installer was
  verified. Records need alarm limits, for instance an ai with HIGH / HIHI
  and HSV / HHSV, then :

      softIoc -S -d alarmtest.db &
      caput TEST:TEMP 9                      # above HIHI, a MAJOR alarm

  and the alarm has to show up in three places :

      # the alarm server published it to Kafka
      ${KAFKA_PREFIX}/bin/kafka-console-consumer.sh --bootstrap-server \\
          ${KAFKA_HOST}:${KAFKA_PORT} --topic ${ALARM_CONFIG} --from-beginning \\
          --property print.key=true --max-messages 10

      # the alarm logger wrote it to Elasticsearch
      curl 'http://${ES_HOST}:${ES_PORT}/_cat/indices?v'
      curl 'http://${ES_HOST}:${ES_PORT}/*alarms_state*/_search?pretty&size=5'

      # the configuration logger committed the configuration itself
      sudo -u ${PHOEBUS_USER} git -C ${ALARM_CONFIG_REPO}/${ALARM_CONFIG} log --oneline

  Channel Access reaches the IOCs listed in
  ${PHOEBUS_PREFIX}/settings.ini and in
  ${SERVICES_PREFIX}/alarm-server/alarm_settings.ini. Both are generated by
  --addr-list, and a machine whose EPICS_CA_AUTO_ADDR_LIST is 'no' will not
  find a local IOC unless 127.0.0.1 is in that list.

WHAT ENDS UP WHERE

  desktop product, settings.ini   ${PHOEBUS_PREFIX}
  desktop launcher                /usr/local/bin/phoebus
  alarm services and settings     ${SERVICES_PREFIX}
  alarm configuration history     ${ALARM_CONFIG_REPO}  (git, one per configuration)
  Kafka                           ${KAFKA_PREFIX}
  Kafka topic data                ${KAFKA_DATA}
  Kafka log4j output              ${KAFKA_LOGS}
  Elasticsearch                   ${ES_PREFIX}
  Elasticsearch data              ${ES_DATA}
  Elasticsearch logs              ${ES_LOGS}
  systemd units                   /etc/systemd/system/kafka.service
                                  /etc/systemd/system/elasticsearch.service
                                  /etc/systemd/system/phoebus-alarm-server.service
                                  /etc/systemd/system/phoebus-alarm-logger.service
                                  /etc/systemd/system/phoebus-alarm-config-logger.service
  installation log                ${LOG_FILE}

  The bundle itself is not installed anywhere : a tarball is unpacked into a
  temporary directory, read, and removed when the run ends. Keep the bundle
  which installed a machine, it pins the exact artifacts that machine runs.

EXAMPLES BY PURPOSE

  Step 1 : build the bundle, on a machine with internet access
    ./${SC_NAME} bundle                          # ./phoebus-bundle + .tar.gz
    ./${SC_NAME} bundle --bundle-dir=/data/phoebus-2026-08
    ./${SC_NAME} bundle --no-pkgs                # the target has its own mirror
    ./${SC_NAME} bundle --with-jdk=no            # the target has a JDK already
    ./${SC_NAME} bundle --src-repo=upstream --src-dir=~/phoebus-upstream
    ./${SC_NAME} bundle --src-repo=fork --src-ref=v5.0.0
    tar tzf phoebus-bundle.tar.gz | head         # what is inside
    cat phoebus-bundle/manifest.txt              # versions, commit, distribution

  Step 2 : install, on the machine without internet access
    ./${SC_NAME} --bundle=phoebus-bundle.tar.gz -y
    ./${SC_NAME} --bundle=/mnt/usb/phoebus-bundle -y     # an unpacked bundle
    ./${SC_NAME} --bundle=phoebus-bundle.tar.gz --dry-run
    ./${SC_NAME} --bundle=phoebus-bundle.tar.gz --force-os -y

  Adapt the installation to the site
    ./${SC_NAME} --bundle=b.tar.gz -y --config=Injector
    ./${SC_NAME} --bundle=b.tar.gz -y --addr-list='10.0.0.255' --pv-default=pva
    ./${SC_NAME} --bundle=b.tar.gz -y --no-desktop        # a headless machine

  Only a part of it, from the bundle
    ./${SC_NAME} --bundle=b.tar.gz kafka elastic service
    ./${SC_NAME} --bundle=b.tar.gz install service

  Is it installed, and is it running ?
    ./${SC_NAME} exist
    ./${SC_NAME} status
    curl 'http://${ES_HOST}:${ES_PORT}/_cluster/health?pretty'

  What is in the bundle

    phoebus-bundle/manifest.txt   distribution, versions, commit
    phoebus-bundle/dist/          product-*.tar.gz, the three service jars,
                                  the alarm server with its lib directory,
                                  and settings.ini of the machine which built it
    phoebus-bundle/tarballs/      Temurin JDK, Kafka, Elasticsearch
    phoebus-bundle/pkgs/          OS packages and their dependencies

  Roughly 1.6 GB, so it travels on a USB stick or as a release asset, never
  in a git repository.

  Remove it
    ./${SC_NAME} uninstall
    sudo rm -rf ${KAFKA_PREFIX} ${ES_PREFIX}

EOF
}

## ----------------------------------------------------------------------------
## Option parsing
## ----------------------------------------------------------------------------
function parse_args
{
    while (( $# > 0 )); do
        case "$1" in
            -y|--yes)                ASSUME_YES="true" ;;
            -n|--dry-run)            DRY_RUN="true" ;;
            --log-file=*)            LOG_FILE="${1#*=}" ;;
            --bundle=*)              BUNDLE="${1#*=}" ;;
            --bundle-dir=*)          BUNDLE_DIR="${1#*=}" ;;
            --no-pkgs)               WITH_PKGS="false" ;;
            --with-jdk=*)            [[ "${1#*=}" =~ ^(no|false|0)$ ]] && WITH_JDK="false" || WITH_JDK="true" ;;
            --no-pack)               BUNDLE_PACK="false" ;;
            --force-os)              FORCE_OS="true" ;;
            --src-repo=*)            SRC_REPO="${1#*=}";        OPT_SET[SRC_REPO]=1 ;;
            --src-ref=*)             SRC_REF="${1#*=}";         OPT_SET[SRC_REF]=1 ;;
            --src-dir=*)             SRC_DIR="${1#*=}";         OPT_SET[SRC_DIR]=1 ;;
            --config=*)              ALARM_CONFIG="${1#*=}";    OPT_SET[ALARM_CONFIG]=1 ;;
            --user=*)                PHOEBUS_USER="${1#*=}" ; OPT_SET[PHOEBUS_USER]=1 ;;
            --prefix=*)              PHOEBUS_PREFIX="${1#*=}" ;;
            --services-prefix=*)     SERVICES_PREFIX="${1#*=}" ;;
            --no-config-logger)      WITH_CONFIG_LOGGER="false" ;;
            --no-save-restore)       WITH_SAVE_RESTORE="false" ;;
            --save-restore-port=*)   SAVE_RESTORE_PORT="${1#*=}" ;;
            --no-desktop)            WITH_DESKTOP="false" ;;
            --kafka-version=*)       KAFKA_VER="${1#*=}" ;;
            --kafka-prefix=*)        KAFKA_PREFIX="${1#*=}" ;;
            --kafka-host=*)          KAFKA_HOST="${1#*=}" ; OPT_SET[KAFKA_HOST]=1 ;;
            --es-version=*)          ES_VER="${1#*=}" ;;
            --es-prefix=*)           ES_PREFIX="${1#*=}" ;;
            --es-host=*)             ES_HOST="${1#*=}" ; OPT_SET[ES_HOST]=1 ;;
            --pv-default=*)          EPICS_PV_DEFAULT="${1#*=}" ; OPT_SET[EPICS_PV_DEFAULT]=1 ;;
            --addr-list=*)           EPICS_ADDR_LIST="${1#*=}" ; OPT_SET[EPICS_ADDR_LIST]=1 ;;
            --no-auto-addr-list)     EPICS_AUTO_ADDR_LIST="false"; OPT_SET[EPICS_AUTO_ADDR_LIST]=1 ;;
            --jdk-major=*)           JDK_MAJOR="${1#*=}" ;;
            --maven-opts=*)          MAVEN_USER_OPTS="${1#*=}" ;;
            --with-tests)            SKIP_TESTS="false" ;;
            --open-firewall)         OPEN_FIREWALL="true" ;;
            -h|--help)               usage; exit 0 ;;
            -*)                      usage; die "unknown option : $1" ;;
            *)                       REQUESTED_STAGES+=("$1") ;;
        esac
        shift
    done

    [[ "${JDK_MAJOR}" =~ ^[0-9]+$ ]] || die "--jdk-major must be a number"
    [[ "${EPICS_PV_DEFAULT}" =~ ^(ca|pva)$ ]] || die "--pv-default must be ca or pva"

    (( ${#REQUESTED_STAGES[@]} == 0 )) && REQUESTED_STAGES=("${ALL_STAGES[@]}")

    local stage s known
    for stage in "${REQUESTED_STAGES[@]}"; do
        known="false"
        for s in "${ALL_STAGES[@]}" "${EXTRA_STAGES[@]}"; do
            [[ "${stage}" == "${s}" ]] && known="true"
        done
        [[ "${known}" == "true" ]] || die "unknown stage : ${stage}, see ${SC_NAME} --help"
    done

    ## the configuration logger is optional, drop its unit when it is not wanted
    if [[ "${WITH_CONFIG_LOGGER}" != "true" ]]; then
        ALARM_UNITS=(phoebus-alarm-server.service phoebus-alarm-logger.service)
    fi
    [[ "${WITH_SAVE_RESTORE}" == "true" ]] || SR_UNITS=()
}

function stages_are_read_only
{
    local stage s found
    for stage in "${REQUESTED_STAGES[@]}"; do
        found="false"
        for s in "${NOSUDO_STAGES[@]}"; do
            [[ "${stage}" == "${s}" ]] && found="true"
        done
        [[ "${found}" == "true" ]] || return 1
    done
    return 0
}

## ----------------------------------------------------------------------------
## Bundle : creation on the online machine, opening on the offline one
## ----------------------------------------------------------------------------
function bundle_manifest_value
{
    local key="$1"
    [[ -r "${BUNDLE_WORK}/manifest.txt" ]] || return 0
    awk -F'=' -v k="${key}" '$1 == k {sub(/^[^=]*=/, ""); print; exit}' "${BUNDLE_WORK}/manifest.txt"
}

## Make the bundle usable : a directory is taken as is, a tarball is extracted
function open_bundle
{
    [[ -n "${BUNDLE}" ]] || return 0
    [[ -e "${BUNDLE}" ]] || die "the bundle ${BUNDLE} does not exist"

    if [[ -d "${BUNDLE}" ]]; then
        BUNDLE_WORK="$(realpath "${BUNDLE}")"
    elif [[ "${DRY_RUN}" == "true" ]]; then
        ## unpacking 1.6 GB to show a plan is pointless, only the manifest is
        ## read and the layout it describes is mimicked
        BUNDLE_TMP="$(mktemp -d)"
        BUNDLE_WORK="${BUNDLE_TMP}/bundle"
        mkdir -p "${BUNDLE_WORK}"
        info "reading the manifest of $(basename "${BUNDLE}")"
        tar -C "${BUNDLE_WORK}" --strip-components=1 --wildcards -xzf "$(realpath "${BUNDLE}")" '*/manifest.txt' 2>/dev/null \
            || warn "no manifest could be read from the tarball"
        mkdir -p "${BUNDLE_WORK}/dist" "${BUNDLE_WORK}/tarballs"
        [[ "$(bundle_manifest_value with_pkgs)" == "true" ]] && mkdir -p "${BUNDLE_WORK}/pkgs"
    else
        BUNDLE_TMP="$(mktemp -d)"
        info "extracting $(basename "${BUNDLE}") ..."
        run_sh "tar -C '${BUNDLE_TMP}' -xzf '$(realpath "${BUNDLE}")'"
        BUNDLE_WORK="$(find "${BUNDLE_TMP}" -mindepth 1 -maxdepth 1 -type d | head -1)"
        [[ -n "${BUNDLE_WORK}" ]] || die "the bundle tarball looks empty"
    fi

    [[ -r "${BUNDLE_WORK}/manifest.txt" ]] || die "${BUNDLE_WORK} has no manifest.txt, this is not a bundle"
    ok "bundle : ${BUNDLE_WORK}"
    info "built on $(bundle_manifest_value os_pretty) at $(bundle_manifest_value created)"
    info "phoebus $(bundle_manifest_value phoebus_version), source $(bundle_manifest_value src_commit)"

    local b_id b_major b_arch
    b_id="$(bundle_manifest_value os_id)"
    b_major="$(bundle_manifest_value os_major)"
    b_arch="$(bundle_manifest_value arch)"
    if [[ -d "${BUNDLE_WORK}/pkgs" ]] && [[ "${b_id}" != "${OS_ID}" || "${b_major}" != "${OS_MAJOR}" || "${b_arch}" != "$(uname -m)" ]]; then
        error "the bundle carries ${b_id} ${b_major} ${b_arch} packages, this machine is ${OS_ID} ${OS_MAJOR} $(uname -m)"
        if [[ "${FORCE_OS}" == "true" ]]; then
            warn "--force-os given, the OS packages of the bundle are ignored"
            WITH_PKGS="false"
        else
            error "rebuild the bundle on a ${OS_ID} ${OS_MAJOR} machine, or install the OS"
            error "packages from your own mirror and add --force-os"
            die "the bundle does not fit this machine"
        fi
    fi
    return 0
}

function close_bundle
{
    [[ -n "${BUNDLE_TMP}" && -d "${BUNDLE_TMP}" ]] && rm -rf "${BUNDLE_TMP}"
    return 0
}

## Where an artifact comes from : the bundle when one is open, the build
## directory otherwise. Echoes the path, nothing when there is none.
function artifact
{
    local build_glob="$1" bundle_glob="$2" f=""
    if [[ -n "${BUNDLE_WORK}" ]]; then
        f="$(compgen -G "${BUNDLE_WORK}/dist/${bundle_glob}" | head -1 || true)"
        ## a --dry-run only reads the manifest, nothing is unpacked : show the
        ## pattern so the plan stays readable
        [[ -z "${f}" && "${DRY_RUN}" == "true" ]] && f="${BUNDLE_WORK}/dist/${bundle_glob}"
    else
        f="$(compgen -G "${SRC_DIR}/${build_glob}" | head -1 || true)"
    fi
    printf '%s' "${f}"
    return 0
}

## --- creation ---------------------------------------------------------------
## No JDK and no Maven in the package list : the JDK travels as a tarball and
## the offline machine never builds. On EL the distribution JDK would also drag
## in the whole graphical stack.
function bundle_pkg_list
{
    if [[ "${OS_FAMILY}" == "debian" ]]; then
        printf '%s\n' ca-certificates wget curl git tar unzip procps
        [[ "${WITH_DESKTOP}" == "true" ]] && printf '%s\n' libgtk-3-0 libxtst6
        [[ "${WITH_JDK}" == "true" ]] || printf '%s\n' "openjdk-${JDK_MAJOR}-jdk"
    else
        printf '%s\n' ca-certificates wget curl git tar unzip procps-ng which
        [[ "${WITH_DESKTOP}" == "true" ]] && printf '%s\n' gtk3 libXtst
        [[ "${WITH_JDK}" == "true" ]] || printf '%s\n' "java-${JDK_MAJOR}-openjdk-devel"
    fi
}

function bundle_packages
{
    local dest="$1" pkgs=()
    mapfile -t pkgs < <(bundle_pkg_list)
    run mkdir -p "${dest}"
    if [[ "${OS_FAMILY}" == "debian" ]]; then
        warn "on Debian only the packages which are not installed yet can be downloaded"
        warn "reliably, build the bundle on a machine without them or use your own mirror"
        run mkdir -p "${dest}/partial"
        try_sh "sudo apt-get install --reinstall --download-only -y -o Dir::Cache::archives='${dest}' ${pkgs[*]}" \
            || warn "apt-get could not download every package"
        try_sh "sudo chown -R $(id -un):$(id -gn) '${dest}'" || true
        rmdir "${dest}/partial" 2>/dev/null || true
    else
        run_sh "dnf download -y --setopt='*.skip_if_unavailable=1' --resolve --alldeps --destdir='${dest}' ${pkgs[*]} < /dev/null"
    fi
    ok "$(find "${dest}" -name '*.rpm' -o -name '*.deb' 2>/dev/null | wc -l) package files, $(du -sh "${dest}" 2>/dev/null | cut -f1)"
}

function bundle_tarballs
{
    local dest="$1" src url arch
    run mkdir -p "${dest}"

    src="kafka_${KAFKA_SCALA}-${KAFKA_VER}.tgz"
    if [[ -s "${dest}/${src}" ]]; then
        ok "${src} is already in the bundle"
    else
        info "downloading ${src}"
        run_sh "curl -fsSL 'https://archive.apache.org/dist/kafka/${KAFKA_VER}/${src}' -o '${dest}/${src}'"
    fi

    case "$(uname -m)" in
        x86_64)  arch="x86_64" ;;
        aarch64) arch="aarch64" ;;
        *) die "unsupported CPU architecture : $(uname -m)" ;;
    esac
    src="elasticsearch-${ES_VER}-linux-${arch}.tar.gz"
    if [[ -s "${dest}/${src}" ]]; then
        ok "${src} is already in the bundle"
    else
        info "downloading ${src}, this one is large"
        run_sh "curl -fsSL 'https://artifacts.elastic.co/downloads/elasticsearch/${src}' -o '${dest}/${src}'"
    fi

    if [[ "${WITH_JDK}" == "true" ]]; then
        case "$(uname -m)" in
            x86_64)  arch="x64" ;;
            aarch64) arch="aarch64" ;;
        esac
        src="temurin-jdk-${JDK_MAJOR}-linux-${arch}.tar.gz"
        if [[ -s "${dest}/${src}" ]]; then
            ok "the Temurin JDK is already in the bundle"
        else
            info "downloading the Eclipse Temurin JDK ${JDK_MAJOR} (${arch})"
            run_sh "curl -fsSL 'https://api.adoptium.net/v3/binary/latest/${JDK_MAJOR}/ga/linux/${arch}/jdk/hotspot/normal/eclipse' -o '${dest}/${src}'"
        fi
    fi
    ok "tarballs : $(du -sh "${dest}" 2>/dev/null | cut -f1)"
}

## What Maven produced : the product and the services, each one self contained
function bundle_dist
{
    local dest="$1" f
    run mkdir -p "${dest}"

    for f in "${TOP}"/services/alarm-server/target/service-alarm-server-*-bin.tar.gz \
             "${TOP}"/services/alarm-logger/target/service-alarm-logger-*.jar \
             "${TOP}"/services/alarm-config-logger/target/service-alarm-config-logger-*.jar; do
        [[ -e "${f}" ]] || die "${f##*/} is missing, the build did not produce everything"
        run cp -f "${f}" "${dest}/"
    done
    if [[ "${WITH_DESKTOP}" == "true" ]]; then
        f="$(compgen -G "${SRC_DIR}/phoebus-product/target/product-*.tar.gz" | head -1)" \
            || die "no product tarball, the build did not produce everything"
        run cp -f "${f}" "${dest}/"
    fi
    ## the settings of this site travel along, they are the reference file
    [[ -r "${PHOEBUS_PREFIX}/settings.ini" ]] && run cp -f "${PHOEBUS_PREFIX}/settings.ini" "${dest}/settings.ini"
    ok "dist : $(du -sh "${dest}" 2>/dev/null | cut -f1)"
}

function stage_bundle
{
    banner "Stage : bundle - collect everything an offline machine needs"

    [[ "${DRY_RUN}" == "true" ]] && { info "would create the bundle in ${BUNDLE_DIR}"; return 0; }

    local dest; dest="$(realpath -m "${BUNDLE_DIR}")"
    [[ -e "${dest}" && ! -d "${dest}" ]] && die "${dest} exists and is not a directory"
    [[ -d "${dest}" ]] && info "${dest} exists, the parts which are already complete are kept"
    run mkdir -p "${dest}"

    ## 1. the artifacts, built here when they are not there yet
    ensure_java_known
    if ! compgen -G "${SRC_DIR}/services/alarm-server/target/service-alarm-server-*-bin.tar.gz" >/dev/null; then
        info "no build output yet, fetching the source and building first"
        stage_src
        stage_build
    fi
    bundle_dist "${dest}/dist"

    ## 2. the third party tarballs
    bundle_tarballs "${dest}/tarballs"

    ## 3. the OS packages
    if [[ "${WITH_PKGS}" == "true" ]]; then
        bundle_packages "${dest}/pkgs"
    else
        info "--no-pkgs : the target machine installs the OS packages from its own mirror"
    fi

    ## 4. the manifest and this installer
    run cp -f "${SC_SCRIPT}" "${dest}/${SC_NAME}"
    write_config_file "${dest}/manifest.txt" "\
bundle_version=${BUNDLE_VERSION}
created=$(date '+%Y-%m-%d %H:%M:%S %z')
created_on=$(hostname)
os_id=${OS_ID}
os_major=${OS_MAJOR}
os_pretty=${OS_PRETTY}
arch=$(uname -m)
phoebus_version=$(basename "$(compgen -G "${SRC_DIR}/phoebus-product/target/product-*.jar" | head -1)" .jar | sed 's/^product-//')
src_repo=${SRC_REPO}
src_ref=${SRC_REF}
src_commit=$(git -C "${SRC_DIR}" log --oneline -1 2>/dev/null || echo unknown)
jdk_major=${JDK_MAJOR}
kafka_version=${KAFKA_VER}
es_version=${ES_VER}
alarm_config=${ALARM_CONFIG}
with_pkgs=${WITH_PKGS}
with_jdk=${WITH_JDK}
with_desktop=${WITH_DESKTOP}
"

    ## 5. pack it
    if [[ "${BUNDLE_PACK}" == "true" ]]; then
        info "packing ${dest}.tar.gz"
        run rm -f "${dest}.tar.gz"
        run_sh "tar -C '$(dirname "${dest}")' -czf '${dest}.tar.gz' '$(basename "${dest}")'"
        ok "bundle : ${dest}.tar.gz  ($(du -sh "${dest}.tar.gz" | cut -f1))"
    fi
    printf '\n'
    print_kv "bundle directory" "${dest}  ($(du -sh "${dest}" | cut -f1))"
    print_kv "install it with"  "./${SC_NAME} --bundle=${dest}.tar.gz -y"
    printf '\n'
    return 0
}

## Write a file as the invoking user, used for the manifest
function write_config_file
{
    local dest="$1"; shift
    if [[ "${DRY_RUN}" == "true" ]]; then
        printf '%s[dry]%s would write %s\n' "${C_YEL}" "${C_OFF}" "${dest}"
        return 0
    fi
    printf '%s' "$*" > "${dest}"
    log "--- ${dest} ---"; log "$*"
    ok "generated ${dest}"
}

## ----------------------------------------------------------------------------
## OS detection and privileges
## ----------------------------------------------------------------------------
function detect_os
{
    [[ -r /etc/os-release ]] || die "/etc/os-release is missing, cannot detect the distribution"
    # shellcheck disable=SC1091
    OS_ID="$(. /etc/os-release; echo "${ID:-}")"
    # shellcheck disable=SC1091
    OS_LIKE="$(. /etc/os-release; echo "${ID_LIKE:-}")"
    # shellcheck disable=SC1091
    OS_VERSION_ID="$(. /etc/os-release; echo "${VERSION_ID:-}")"
    # shellcheck disable=SC1091
    OS_PRETTY="$(. /etc/os-release; echo "${PRETTY_NAME:-}")"
    OS_MAJOR="${OS_VERSION_ID%%.*}"

    case " ${OS_ID} ${OS_LIKE} " in
        *" debian "*|*" ubuntu "*)                        OS_FAMILY="debian" ;;
        *" rhel "*|*" fedora "*|*" centos "*|*" rocky "*) OS_FAMILY="rhel" ;;
        *) die "unsupported distribution : ${OS_PRETTY}" ;;
    esac
    info "distribution : ${OS_PRETTY}  (family ${OS_FAMILY}, major ${OS_MAJOR})"
}

function check_sudo
{
    [[ "$(id -u)" == "0" ]] && die "run ${SC_NAME} as a normal user owning sudo rights, not as root"
    command -v sudo >/dev/null 2>&1 || die "sudo is required but is not installed"
    [[ "${DRY_RUN}" == "true" ]] && return 0

    if ! sudo -n true 2>/dev/null; then
        info "the installation needs sudo privileges, please type your password"
        sudo -v || die "sudo authentication failed"
    fi
    ( while kill -0 "$$" 2>/dev/null; do sudo -n true 2>/dev/null; sleep 45; done & ) >/dev/null 2>&1
    return 0
}

function selinux_note
{
    command -v getenforce >/dev/null 2>&1 || return 0
    [[ "$(getenforce 2>/dev/null || echo Disabled)" == "Enforcing" ]] || return 0
    warn "SELinux is Enforcing : these services run unconfined from systemd, but if one"
    warn "cannot write its data directory, check it with 'sudo ausearch -m avc -ts recent'"
}

## ----------------------------------------------------------------------------
## Packages
## ----------------------------------------------------------------------------
DNF_OPTS=(-y "--setopt=*.skip_if_unavailable=1")

function pkg_install
{
    if [[ "${OS_FAMILY}" == "debian" ]]; then
        run sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y "$@"
    else
        run sudo dnf "${DNF_OPTS[@]}" install "$@"
    fi
}

function pkg_install_optional
{
    local p rc
    for p in "$@"; do
        if [[ "${DRY_RUN}" == "true" ]]; then
            printf '%s[dry]%s install optional package %s\n' "${C_YEL}" "${C_OFF}" "${p}"
            continue
        fi
        rc=0
        if [[ "${OS_FAMILY}" == "debian" ]]; then
            try_sh "sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y '${p}'" >/dev/null 2>&1 || rc=$?
        else
            try_sh "sudo dnf -y '--setopt=*.skip_if_unavailable=1' install '${p}'" >/dev/null 2>&1 || rc=$?
        fi
        (( rc == 0 )) && ok "optional package '${p}' is installed" \
                      || warn "optional package '${p}' could not be installed, skipped"
    done
}

function pkg_available
{
    local p="$1"
    if [[ "${OS_FAMILY}" == "debian" ]]; then
        [[ -n "$(apt-cache policy "${p}" 2>/dev/null | awk '/Candidate:/ && $2 != "(none)" {print $2}')" ]] && return 0
        return 1
    fi
    local dnfq=(dnf -q "--setopt=*.skip_if_unavailable=1")
    "${dnfq[@]}" list --available "${p}" </dev/null >/dev/null 2>&1 && return 0
    "${dnfq[@]}" list --installed "${p}" </dev/null >/dev/null 2>&1 && return 0
    return 1
}

## Install the package files carried by the bundle, without any repository
function pkgs_from_bundle
{
    local dir="${BUNDLE_WORK}/pkgs"
    if [[ "${OS_FAMILY}" == "debian" ]]; then
        compgen -G "${dir}/*.deb" >/dev/null || { warn "no .deb in the bundle"; return 0; }
        try_sh "sudo dpkg -i ${dir}/*.deb" || try_sh "sudo dpkg -i ${dir}/*.deb" \
            || die "the packages of the bundle could not be installed"
        try sudo dpkg --configure -a || true
    else
        compgen -G "${dir}/*.rpm" >/dev/null || { warn "no .rpm in the bundle"; return 0; }
        if ! try_sh "sudo dnf install -y --disablerepo='*' ${dir}/*.rpm"; then
            error "the packages of the bundle conflict with what this machine already has."
            error "bring the machine to the patch level of the one which built the bundle,"
            error "or skip them and install from your own mirror :"
            error "    ./${SC_NAME} --bundle=... --force-os ..."
            die "the OS packages could not be installed"
        fi
    fi
    ok "the packages of the bundle are installed"
}

function stage_pkgs
{
    banner "Stage : pkgs - operating system packages"

    if [[ -n "${BUNDLE_WORK}" ]]; then
        if [[ "${WITH_PKGS}" == "true" && -d "${BUNDLE_WORK}/pkgs" ]]; then
            info "installing the OS packages from the bundle"
            pkgs_from_bundle
        else
            warn "the bundle has no OS packages, install these from your own mirror :"
            warn "    $(bundle_pkg_list | tr '\n' ' ')"
        fi
        return 0
    fi

    if [[ "${OS_FAMILY}" == "debian" ]]; then
        try sudo env DEBIAN_FRONTEND=noninteractive apt-get update -y \
            || warn "apt-get update reported an error, continuing with the cached lists"
        pkg_install ca-certificates wget curl git tar unzip make gcc procps
        ## the desktop product needs the usual X libraries of a JavaFX application
        [[ "${WITH_DESKTOP}" == "true" ]] && pkg_install_optional libgtk-3-0 libxtst6 mesa-utils
    else
        run sudo dnf "${DNF_OPTS[@]}" install dnf-plugins-core
        pkg_install ca-certificates wget curl git tar unzip make gcc procps-ng which
        [[ "${WITH_DESKTOP}" == "true" ]] && pkg_install_optional gtk3 libXtst mesa-libGL
    fi
    ok "the operating system packages are ready"
}

## ----------------------------------------------------------------------------
## JDK and Maven
## ----------------------------------------------------------------------------
function find_system_jdk
{
    local candidate javac_ver
    for candidate in "${JAVA_ENV_PREFIX}/JDK" \
                     /usr/lib/jvm/java-*-openjdk* \
                     /usr/lib/jvm/temurin-* \
                     /usr/lib/jvm/jdk-* \
                     /usr/lib/jvm/*; do
        [[ -x "${candidate}/bin/javac" ]] || continue
        javac_ver="$("${candidate}/bin/javac" -version 2>&1 | awk '{print $2}' | cut -d. -f1)"
        [[ "${javac_ver}" =~ ^[0-9]+$ ]] || continue
        if (( javac_ver >= JDK_MAJOR )); then
            realpath "${candidate}"
            return 0
        fi
    done
    return 1
}

function install_jdk_package
{
    local pkg
    if [[ "${OS_FAMILY}" == "debian" ]]; then
        pkg="openjdk-${JDK_MAJOR}-jdk"
    else
        pkg="java-${JDK_MAJOR}-openjdk-devel"
    fi
    if pkg_available "${pkg}"; then
        info "installing the distribution JDK package ${pkg}"
        pkg_install "${pkg}"
        return 0
    fi
    warn "${pkg} is not available on ${OS_PRETTY}"
    return 1
}

function install_jdk_tarball
{
    local arch url dest tmp
    case "$(uname -m)" in
        x86_64)  arch="x64" ;;
        aarch64) arch="aarch64" ;;
        *) die "unsupported CPU architecture : $(uname -m)" ;;
    esac
    url="https://api.adoptium.net/v3/binary/latest/${JDK_MAJOR}/ga/linux/${arch}/jdk/hotspot/normal/eclipse"
    dest="${JAVA_ENV_PREFIX}/JDK"
    info "downloading the Eclipse Temurin JDK ${JDK_MAJOR} (${arch})"
    tmp="$(mktemp -d)"
    try_sh "curl -fsSL '${url}' -o '${tmp}/jdk.tar.gz'" || die "cannot download the JDK tarball"
    run sudo install -d "${JAVA_ENV_PREFIX}"
    run sudo rm -rf "${dest}"
    run sudo install -d "${dest}"
    run_sh "sudo tar -C '${dest}' -xzf '${tmp}/jdk.tar.gz' --strip-components=1"
    rm -rf "${tmp}"
    ok "JDK ${JDK_MAJOR} installed into ${dest}"
}

function install_maven_tarball
{
    local src url dest tmp
    src="apache-maven-${MAVEN_VER}-bin.tar.gz"
    url="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VER}/binaries/${src}"
    dest="${JAVA_ENV_PREFIX}/MAVEN"
    info "downloading Apache Maven ${MAVEN_VER}"
    tmp="$(mktemp -d)"
    try_sh "curl -fsSL '${url}' -o '${tmp}/${src}'" || die "cannot download ${url}"
    if try_sh "curl -fsSL '${url}.sha512' -o '${tmp}/${src}.sha512'"; then
        run_sh "cd '${tmp}' && echo \"\$(cat '${src}.sha512')  ${src}\" | sha512sum -c -"
    else
        warn "the sha512 file could not be downloaded, the checksum is not verified"
    fi
    run sudo install -d "${JAVA_ENV_PREFIX}"
    run sudo rm -rf "${dest}"
    run sudo install -d "${dest}"
    run_sh "sudo tar -C '${dest}' -xzf '${tmp}/${src}' --strip-components=1"
    rm -rf "${tmp}"
    ok "Apache Maven ${MAVEN_VER} installed into ${dest}"
}

## Unpack a tarball of the bundle into a destination directory
function untar_bundle_tarball
{
    local pattern="$1" dest="$2" file
    file="$(compgen -G "${BUNDLE_WORK}/tarballs/${pattern}" | head -1)" || return 1
    [[ -n "${file}" ]] || return 1
    run sudo install -d "$(dirname "${dest}")"
    run sudo rm -rf "${dest}"
    run sudo install -d "${dest}"
    run_sh "sudo tar -C '${dest}' -xzf '${file}' --strip-components=1"
    return 0
}

function stage_java
{
    banner "Stage : java - JDK ${JDK_MAJOR}+"

    ## Offline : the JDK comes from the bundle or from its OS packages, and
    ## Maven is never needed because nothing is built here.
    if [[ -n "${BUNDLE_WORK}" ]]; then
        if JAVA_HOME_DETECTED="$(find_system_jdk)"; then
            ok "JDK ${JDK_MAJOR}+ available : ${JAVA_HOME_DETECTED}"
        elif untar_bundle_tarball "temurin-jdk-*.tar.gz" "${JAVA_ENV_PREFIX}/JDK"; then
            ok "the Temurin JDK of the bundle is installed in ${JAVA_ENV_PREFIX}/JDK"
            JAVA_HOME_DETECTED="$(find_system_jdk)" || die "the bundled JDK does not work"
        else
            die "no JDK ${JDK_MAJOR}+ and none in the bundle, rebuild it without --with-jdk=no"
        fi
        info "JAVA_HOME  : ${JAVA_HOME_DETECTED}"
        return 0
    fi

    if JAVA_HOME_DETECTED="$(find_system_jdk)"; then
        ok "JDK ${JDK_MAJOR}+ already available : ${JAVA_HOME_DETECTED}"
    elif install_jdk_package; then
        JAVA_HOME_DETECTED="$(find_system_jdk)" || die "no JDK ${JDK_MAJOR}+ after the package install"
    else
        install_jdk_tarball
        JAVA_HOME_DETECTED="$(find_system_jdk)" || die "no JDK ${JDK_MAJOR}+ after the tarball install"
    fi
    info "JAVA_HOME  : ${JAVA_HOME_DETECTED}"

    if command -v mvn >/dev/null 2>&1 && [[ -z "${MAVEN_HOME_DETECTED}" ]]; then
        MAVEN_HOME_DETECTED="$(dirname "$(dirname "$(readlink -f "$(command -v mvn)")")")"
        ok "Apache Maven already available : ${MAVEN_HOME_DETECTED}"
    elif [[ -x "${JAVA_ENV_PREFIX}/MAVEN/bin/mvn" ]]; then
        MAVEN_HOME_DETECTED="${JAVA_ENV_PREFIX}/MAVEN"
        ok "Apache Maven already available : ${MAVEN_HOME_DETECTED}"
    else
        install_maven_tarball
        MAVEN_HOME_DETECTED="${JAVA_ENV_PREFIX}/MAVEN"
    fi

    try_sh "JAVA_HOME='${JAVA_HOME_DETECTED}' '${MAVEN_HOME_DETECTED}/bin/mvn' -version" \
        || warn "mvn -version failed, check the JDK and Maven installation"
}

## resolve JAVA_HOME / MAVEN_HOME when the java stage is not part of this run
function ensure_java_known
{
    [[ -n "${JAVA_HOME_DETECTED}" ]] && return 0
    JAVA_HOME_DETECTED="$(find_system_jdk || true)"
    if [[ -z "${JAVA_HOME_DETECTED}" ]]; then
        [[ "${DRY_RUN}" == "true" ]] || die "no JDK ${JDK_MAJOR}+ found, run './${SC_NAME} java' first"
        JAVA_HOME_DETECTED="${JAVA_ENV_PREFIX}/JDK"
    fi
    if [[ -x "${JAVA_ENV_PREFIX}/MAVEN/bin/mvn" ]]; then
        MAVEN_HOME_DETECTED="${JAVA_ENV_PREFIX}/MAVEN"
    elif command -v mvn >/dev/null 2>&1; then
        MAVEN_HOME_DETECTED="$(dirname "$(dirname "$(readlink -f "$(command -v mvn)")")")"
    elif [[ -n "${BUNDLE_WORK}" ]]; then
        ## installing from a bundle never builds, Maven is not needed
        MAVEN_HOME_DETECTED=""
    else
        [[ "${DRY_RUN}" == "true" ]] || die "Maven not found, run './${SC_NAME} java' first"
        MAVEN_HOME_DETECTED="${JAVA_ENV_PREFIX}/MAVEN"
    fi
    return 0
}

## ----------------------------------------------------------------------------
## Service accounts
## ----------------------------------------------------------------------------
function ensure_user
{
    local user="$1" home="$2"
    if id "${user}" >/dev/null 2>&1; then
        ok "account ${user} exists"
        return 0
    fi
    info "creating the system account ${user}"
    run sudo useradd --system --home-dir "${home}" --no-create-home --shell /usr/sbin/nologin "${user}" \
        || run sudo useradd --system --home-dir "${home}" --no-create-home --shell /sbin/nologin "${user}"
}

## ----------------------------------------------------------------------------
## Apache Kafka, KRaft mode
## ----------------------------------------------------------------------------
function stage_kafka
{
    banner "Stage : kafka - Apache Kafka ${KAFKA_VER}, KRaft mode"

    ensure_java_known
    ensure_user "${KAFKA_USER}" "${KAFKA_PREFIX}"

    local src url tmp
    src="kafka_${KAFKA_SCALA}-${KAFKA_VER}.tgz"
    url="https://archive.apache.org/dist/kafka/${KAFKA_VER}/${src}"

    if [[ -x "${KAFKA_PREFIX}/bin/kafka-server-start.sh" ]]; then
        ok "Kafka is already installed in ${KAFKA_PREFIX}"
    elif [[ -n "${BUNDLE_WORK}" ]]; then
        info "taking ${src} from the bundle"
        untar_bundle_tarball "kafka_*-${KAFKA_VER}.tgz" "${KAFKA_PREFIX}" \
            || untar_bundle_tarball "kafka_*.tgz" "${KAFKA_PREFIX}" \
            || die "no Kafka tarball in the bundle"
    else
        info "downloading ${src}"
        tmp="$(mktemp -d)"
        try_sh "curl -fsSL '${url}' -o '${tmp}/${src}'" || die "cannot download ${url}"
        run sudo install -d "${KAFKA_PREFIX}"
        run_sh "sudo tar -C '${KAFKA_PREFIX}' -xzf '${tmp}/${src}' --strip-components=1"
        rm -rf "${tmp}"
        ok "Kafka ${KAFKA_VER} installed into ${KAFKA_PREFIX}"
    fi

    run sudo install -d -o "${KAFKA_USER}" -g "${KAFKA_USER}" "${KAFKA_DATA}" "${KAFKA_LOGS}"
    run sudo chown -R "${KAFKA_USER}:${KAFKA_USER}" "${KAFKA_PREFIX}"

    ## KRaft needs a server.properties of its own. Kafka 3.x keeps it in
    ## config/kraft/, Kafka 4.x made it the default config/server.properties.
    local kraft_conf="${KAFKA_PREFIX}/config/kraft/server.properties"
    [[ -e "${kraft_conf}" ]] || kraft_conf="${KAFKA_PREFIX}/config/server.properties"
    if [[ "${DRY_RUN}" != "true" && ! -e "${kraft_conf}" ]]; then
        die "no KRaft configuration found in ${KAFKA_PREFIX}/config"
    fi
    info "KRaft configuration : ${kraft_conf}"

    write_root_file "${kraft_conf}" "\
## Generated by ${SC_NAME} on $(date '+%Y-%m-%d %H:%M:%S')
## Single node Kafka in KRaft mode : this process is both broker and controller,
## no Zookeeper is involved.
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@${KAFKA_HOST}:9093
listeners=PLAINTEXT://:${KAFKA_PORT},CONTROLLER://:9093
advertised.listeners=PLAINTEXT://${KAFKA_HOST}:${KAFKA_PORT}
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
inter.broker.listener.name=PLAINTEXT

log.dirs=${KAFKA_DATA}
num.partitions=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
default.replication.factor=1

## the alarm topics are compacted, keep the cleaner active
log.cleaner.enable=true
auto.create.topics.enable=true
"

    ## format the storage once, the cluster id lives in the data directory
    if [[ "${DRY_RUN}" == "true" ]]; then
        info "would format the KRaft storage in ${KAFKA_DATA}"
    elif [[ -e "${KAFKA_DATA}/meta.properties" ]]; then
        ok "the KRaft storage of ${KAFKA_DATA} is already formatted"
    else
        local cluster_id
        cluster_id="$(sudo -u "${KAFKA_USER}" env JAVA_HOME="${JAVA_HOME_DETECTED}" \
                      "${KAFKA_PREFIX}/bin/kafka-storage.sh" random-uuid)" \
            || die "kafka-storage.sh failed, is JAVA_HOME correct ?"
        info "formatting the KRaft storage, cluster id ${cluster_id}"
        run_sh "sudo -u '${KAFKA_USER}' env JAVA_HOME='${JAVA_HOME_DETECTED}' \
                '${KAFKA_PREFIX}/bin/kafka-storage.sh' format -t '${cluster_id}' -c '${kraft_conf}'"
    fi

    write_root_file /etc/systemd/system/kafka.service "\
# Generated by ${SC_NAME}
[Unit]
Description=Apache Kafka ${KAFKA_VER} (KRaft, single node)
Documentation=https://kafka.apache.org/documentation/
Requires=network.target remote-fs.target
After=network.target remote-fs.target

[Service]
Type=simple
User=${KAFKA_USER}
Group=${KAFKA_USER}
Environment=JAVA_HOME=${JAVA_HOME_DETECTED}
Environment=LOG_DIR=${KAFKA_LOGS}
ExecStart=${KAFKA_PREFIX}/bin/kafka-server-start.sh ${kraft_conf}
ExecStop=${KAFKA_PREFIX}/bin/kafka-server-stop.sh
# a JVM stopped with SIGTERM exits 143, that is a clean shutdown
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
"
    run sudo systemctl daemon-reload
    ok "kafka.service is installed"
}

## ----------------------------------------------------------------------------
## Elasticsearch
## ----------------------------------------------------------------------------
function stage_elastic
{
    banner "Stage : elastic - Elasticsearch ${ES_VER}, single node"

    ensure_user "${ES_USER}" "${ES_PREFIX}"

    local src url tmp arch
    case "$(uname -m)" in
        x86_64)  arch="x86_64" ;;
        aarch64) arch="aarch64" ;;
        *) die "unsupported CPU architecture : $(uname -m)" ;;
    esac
    src="elasticsearch-${ES_VER}-linux-${arch}.tar.gz"
    url="https://artifacts.elastic.co/downloads/elasticsearch/${src}"

    if [[ -x "${ES_PREFIX}/bin/elasticsearch" ]]; then
        ok "Elasticsearch is already installed in ${ES_PREFIX}"
    elif [[ -n "${BUNDLE_WORK}" ]]; then
        info "taking ${src} from the bundle"
        untar_bundle_tarball "elasticsearch-*-linux-*.tar.gz" "${ES_PREFIX}" \
            || die "no Elasticsearch tarball in the bundle"
    else
        info "downloading ${src}"
        tmp="$(mktemp -d)"
        try_sh "curl -fsSL '${url}' -o '${tmp}/${src}'" || die "cannot download ${url}"
        run sudo install -d "${ES_PREFIX}"
        run_sh "sudo tar -C '${ES_PREFIX}' -xzf '${tmp}/${src}' --strip-components=1"
        rm -rf "${tmp}"
        ok "Elasticsearch ${ES_VER} installed into ${ES_PREFIX}"
    fi

    run sudo install -d -o "${ES_USER}" -g "${ES_USER}" "${ES_DATA}" "${ES_LOGS}"
    run sudo chown -R "${ES_USER}:${ES_USER}" "${ES_PREFIX}"

    ## network.host stays on localhost : Elasticsearch then runs in development
    ## mode and skips the bootstrap checks, which is what a single node service
    ## for the alarm logger needs. Security is off, the alarm logger talks to it
    ## without credentials.
    write_root_file "${ES_PREFIX}/config/elasticsearch.yml" "\
## Generated by ${SC_NAME} on $(date '+%Y-%m-%d %H:%M:%S')
cluster.name: phoebus
node.name: phoebus-node-1
path.data: ${ES_DATA}
path.logs: ${ES_LOGS}
network.host: ${ES_HOST}
http.port: ${ES_PORT}
discovery.type: single-node

## no TLS and no authentication : this node only listens on ${ES_HOST}
xpack.security.enabled: false
xpack.security.enrollment.enabled: false
"
    run sudo chown "${ES_USER}:${ES_USER}" "${ES_PREFIX}/config/elasticsearch.yml"

    write_root_file /etc/systemd/system/elasticsearch.service "\
# Generated by ${SC_NAME}
[Unit]
Description=Elasticsearch ${ES_VER} (single node, Phoebus alarm logger backend)
Documentation=https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html
Requires=network.target remote-fs.target
After=network.target remote-fs.target

[Service]
Type=simple
User=${ES_USER}
Group=${ES_USER}
Environment=ES_HOME=${ES_PREFIX}
Environment=ES_PATH_CONF=${ES_PREFIX}/config
# Elasticsearch ships with its own JDK, no JAVA_HOME needed
ExecStart=${ES_PREFIX}/bin/elasticsearch
# a JVM stopped with SIGTERM exits 143, that is a clean shutdown
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
TimeoutStopSec=120
LimitNOFILE=65535
LimitMEMLOCK=infinity

[Install]
WantedBy=multi-user.target
"
    run sudo systemctl daemon-reload
    ok "elasticsearch.service is installed"
}

## ----------------------------------------------------------------------------
## Phoebus source : the repository it comes from and where it lives
## ----------------------------------------------------------------------------
function is_phoebus_src
{
    local dir="$1"
    [[ -r "${dir}/pom.xml" && -d "${dir}/phoebus-product" && -d "${dir}/services" ]]
}

## 'fork' and 'upstream' are shortcuts for the two repositories a site normally
## picks between, anything else is taken as a git URL.
function resolve_src_repo
{
    case "${SRC_REPO}" in
        fork|FORK)         SRC_REPO="${SRC_REPO_FORK}" ;;
        upstream|UPSTREAM) SRC_REPO="${SRC_REPO_UPSTREAM}" ;;
    esac
}

## Decide which directory holds the source and which repository it belongs to :
## 1. --src-dir when it was given
## 2. the directory of this script when it already is a checkout
## 3. ./phoebus-src otherwise, which the 'src' stage clones
function resolve_src
{
    resolve_src_repo

    if [[ -z "${SRC_DIR}" ]]; then
        if is_phoebus_src "${TOP}"; then
            SRC_DIR="${TOP}"
        else
            SRC_DIR="${PWD}/phoebus-src"
        fi
    fi

    ## Without --src-repo, an existing checkout keeps its own origin : running
    ## the installer inside a clone must not silently point it somewhere else.
    if [[ -z "${SRC_REPO}" ]]; then
        if [[ -d "${SRC_DIR}/.git" ]] && command -v git >/dev/null 2>&1; then
            SRC_REPO="$(git -C "${SRC_DIR}" remote get-url origin 2>/dev/null || echo "${SRC_REPO_FORK}")"
        else
            SRC_REPO="${SRC_REPO_FORK}"
        fi
    fi
    return 0
}

function stage_src
{
    banner "Stage : src - the Phoebus source"

    if is_phoebus_src "${SRC_DIR}"; then
        ok "source : ${SRC_DIR}"
        if [[ -d "${SRC_DIR}/.git" ]] && command -v git >/dev/null 2>&1; then
            local origin; origin="$(git -C "${SRC_DIR}" remote get-url origin 2>/dev/null || echo unknown)"
            print_kv "origin" "${origin}"
            print_kv "checked out" "$(git -C "${SRC_DIR}" log --oneline -1 2>/dev/null || echo unknown)"

            ## a different repository was asked for : say so instead of
            ## rewriting the remote of a tree which may carry local work
            if [[ -n "${OPT_SET[SRC_REPO]:-}" && "${origin}" != "${SRC_REPO}" ]]; then
                warn "this checkout follows ${origin}, not the requested ${SRC_REPO}"
                warn "clone the other one somewhere else :"
                warn "    ./${SC_NAME} --src-repo=${SRC_REPO} --src-dir=/path/to/new/clone src"
            fi
            ## an explicit --src-ref is checked out, otherwise the tree is left alone
            if [[ -n "${OPT_SET[SRC_REF]:-}" ]]; then
                info "checking out ${SRC_REF}"
                try_sh "git -C '${SRC_DIR}' fetch --all --tags" || warn "git fetch failed, using what is there"
                run_sh "git -C '${SRC_DIR}' checkout '${SRC_REF}'"
                try_sh "git -C '${SRC_DIR}' pull --ff-only" || true
            fi
        fi
        return 0
    fi

    [[ "${DRY_RUN}" == "true" ]] && { info "would clone ${SRC_REPO} (${SRC_REF}) into ${SRC_DIR}"; return 0; }
    command -v git >/dev/null 2>&1 || die "git is needed to clone the source, run './${SC_NAME} pkgs' first"

    info "cloning ${SRC_REPO} (${SRC_REF}) into ${SRC_DIR}"
    try_sh "git clone --branch '${SRC_REF}' '${SRC_REPO}' '${SRC_DIR}'" \
        || run_sh "git clone '${SRC_REPO}' '${SRC_DIR}' && git -C '${SRC_DIR}' checkout '${SRC_REF}'"
    is_phoebus_src "${SRC_DIR}" || die "${SRC_DIR} does not look like a Phoebus source tree after the clone"
    ok "source : ${SRC_DIR}  ($(git -C "${SRC_DIR}" log --oneline -1 2>/dev/null || echo unknown))"
    return 0
}

## ----------------------------------------------------------------------------
## Maven build
## ----------------------------------------------------------------------------
function mvn_run
{
    local opts="${MAVEN_NET_OPTS} ${MAVEN_USER_OPTS}"
    try_sh "cd '${SRC_DIR}' && JAVA_HOME='${JAVA_HOME_DETECTED}' '${MAVEN_HOME_DETECTED}/bin/mvn' ${opts} $*"
}

function stage_build
{
    banner "Stage : build - Maven build of Phoebus"

    is_phoebus_src "${SRC_DIR}" \
        || die "no Phoebus source in ${SRC_DIR}, run './${SC_NAME} src' first"
    ensure_java_known
    local skip=""
    [[ "${SKIP_TESTS}" == "true" ]] && skip="-DskipTests"

    ## 1. the target platform, every external dependency lands in
    ##    dependencies/target/lib
    info "building the target platform (dependencies/pom.xml)"
    mvn_run "clean verify -f dependencies/pom.xml" || die "the target platform build failed"

    ## 2. everything else
    info "building the Phoebus stack, this takes a while"
    mvn_run "${skip} clean install" || die "the Phoebus build failed"

    [[ "${DRY_RUN}" == "true" ]] && return 0

    local missing=()
    compgen -G "${SRC_DIR}/services/alarm-server/target/service-alarm-server-*.jar" >/dev/null || missing+=("alarm-server")
    compgen -G "${SRC_DIR}/services/alarm-logger/target/service-alarm-logger-*.jar" >/dev/null || missing+=("alarm-logger")
    [[ "${WITH_CONFIG_LOGGER}" == "true" ]] && \
        { compgen -G "${SRC_DIR}/services/alarm-config-logger/target/service-alarm-config-logger-*.jar" >/dev/null || missing+=("alarm-config-logger"); }
    [[ "${WITH_SAVE_RESTORE}" == "true" ]] && \
        { compgen -G "${SRC_DIR}/services/save-and-restore/target/service-save-and-restore-*.jar" >/dev/null || missing+=("save-and-restore"); }
    [[ "${WITH_DESKTOP}" == "true" ]] && \
        { compgen -G "${SRC_DIR}/phoebus-product/target/product-*.jar" >/dev/null || missing+=("phoebus-product"); }

    (( ${#missing[@]} > 0 )) && die "the build produced no jar for : ${missing[*]}"
    ok "every jar was produced"
}

## ----------------------------------------------------------------------------
## Installation
## ----------------------------------------------------------------------------
## install_service <name> <jar glob> : jar, launcher and log directory
function install_service_jar
{
    local name="$1" glob="$2" dest="${SERVICES_PREFIX}/$1"
    local jar
    run sudo install -d -o "${PHOEBUS_USER}" -g "${PHOEBUS_USER}" "${dest}" "${dest}/logs"
    if [[ "${DRY_RUN}" == "true" ]]; then
        info "would install ${glob} into ${dest}"
        return 0
    fi
    jar="$(compgen -G "${glob}" | head -1)" || die "no jar matching ${glob}, run './${SC_NAME} build'"
    run sudo install -o "${PHOEBUS_USER}" -g "${PHOEBUS_USER}" -m 644 "${jar}" "${dest}/$(basename "${jar}")"
    ## a stable name, so the systemd unit does not carry the version
    run_sh "sudo ln -sfn '${dest}/$(basename "${jar}")' '${dest}/${name}.jar'"

    ## Not every service jar carries its dependencies : the alarm server is a
    ## thin jar whose manifest has 'Class-Path: lib/...', so that directory has
    ## to travel with it. A Spring Boot jar has no lib directory and needs none.
    local libdir="$(dirname "${jar}")/lib"
    if [[ -d "${libdir}" ]]; then
        info "${name} : copying $(ls -1 "${libdir}" | wc -l) dependency jars"
        run_sh "sudo rm -rf '${dest}/lib' && sudo cp -a '${libdir}' '${dest}/lib'"
        run sudo chown -R "${PHOEBUS_USER}:${PHOEBUS_USER}" "${dest}/lib"
    fi
    ok "${name} : $(basename "${jar}")"
}

## Install a service from the binary distribution tarball of the bundle. The
## tarball carries one top directory with the jar and, for a thin jar, its lib.
function install_service_from_bundle_tar
{
    local name="$1" pattern="$2" dest="${SERVICES_PREFIX}/$1" file tmp
    run sudo install -d -o "${PHOEBUS_USER}" -g "${PHOEBUS_USER}" "${dest}" "${dest}/logs"
    if [[ "${DRY_RUN}" == "true" ]]; then
        info "would install ${pattern} into ${dest}"
        return 0
    fi
    file="$(compgen -G "${BUNDLE_WORK}/dist/${pattern}" | head -1)" \
        || die "no ${pattern} in the bundle"
    ## extracted straight into the destination : going through a temporary
    ## directory and 'cp -a tmp/. dest/' would stamp the mode and the owner of
    ## that temporary directory (0700, the invoking user) onto dest
    run_sh "sudo tar -C '${dest}' -xzf '${file}' --strip-components=1"
    local jar; jar="$(sudo sh -c "ls -1 '${dest}'/service-${name}-*.jar 2>/dev/null" | head -1)"
    [[ -n "${jar}" ]] || die "the distribution of ${name} carries no jar"
    run_sh "sudo ln -sfn '${jar}' '${dest}/${name}.jar'"
    run sudo chown -R "${PHOEBUS_USER}:${PHOEBUS_USER}" "${dest}"
    run sudo chmod 0755 "${dest}"
    run_sh "sudo chmod -R a+rX '${dest}'"
    ok "${name} : $(basename "${jar}") with $(sudo sh -c "ls -1 '${dest}/lib' 2>/dev/null | wc -l") dependency jars"
}

## The settings of an installation live in its settings.ini. A partial run such
## as './SCRIPT install' must not reset what a site configured, so every value
## which was not given on the command line is read back from the installed file,
## or from the one the bundle carries.
function seed_from_settings_file
{
    local file="" line key value
    if [[ -n "${BUNDLE_WORK:-}" && -r "${BUNDLE_WORK}/dist/settings.ini" && ! -r "${PHOEBUS_PREFIX}/settings.ini" ]]; then
        file="${BUNDLE_WORK}/dist/settings.ini"
    elif [[ -r "${PHOEBUS_PREFIX}/settings.ini" ]]; then
        file="${PHOEBUS_PREFIX}/settings.ini"
    else
        return 0
    fi

    while IFS= read -r line; do
        [[ "${line}" =~ ^[[:space:]]*# || -z "${line}" ]] && continue
        key="${line%%=*}"; value="${line#*=}"
        case "${key}" in
            org.phoebus.pv/default)
                [[ -n "${OPT_SET[EPICS_PV_DEFAULT]:-}" ]] || EPICS_PV_DEFAULT="${value}" ;;
            org.phoebus.pv.ca/addr_list)
                [[ -n "${OPT_SET[EPICS_ADDR_LIST]:-}" ]] || EPICS_ADDR_LIST="${value}" ;;
            org.phoebus.pv.ca/auto_addr_list)
                [[ -n "${OPT_SET[EPICS_AUTO_ADDR_LIST]:-}" ]] || EPICS_AUTO_ADDR_LIST="${value}" ;;
            org.phoebus.applications.alarm/config_name)
                [[ -n "${OPT_SET[ALARM_CONFIG]:-}" ]] || ALARM_CONFIG="${value}" ;;
            org.phoebus.applications.alarm/server)
                if [[ -z "${OPT_SET[KAFKA_HOST]:-}" && "${value}" == *:* ]]; then
                    KAFKA_HOST="${value%%:*}"; KAFKA_PORT="${value##*:}"
                fi ;;
        esac
    done < "${file}"
    info "settings kept from ${file}"
    return 0
}

function stage_install
{
    banner "Stage : install - product, services and their settings"

    ensure_java_known
    seed_from_settings_file
    ensure_user "${PHOEBUS_USER}" "${SERVICES_PREFIX}"
    run sudo install -d "${SERVICES_PREFIX}"

    ## --- alarm server -------------------------------------------------------
    ## From a bundle the alarm server arrives as its binary distribution, a
    ## tarball holding the thin jar and the lib directory its manifest needs.
    if [[ -n "${BUNDLE_WORK}" ]]; then
        install_service_from_bundle_tar "alarm-server" "service-alarm-server-*-bin.tar.gz"
    else
        install_service_jar "alarm-server" "${SRC_DIR}/services/alarm-server/target/service-alarm-server-*.jar"
    fi

    ## Phoebus settings file : PV connectivity and the alarm configuration.
    ## The alarm server reads it through -settings.
    local addr_line=""
    [[ -n "${EPICS_ADDR_LIST}" ]] && addr_line="org.phoebus.pv.ca/addr_list=${EPICS_ADDR_LIST}"
    write_root_file "${SERVICES_PREFIX}/alarm-server/alarm_settings.ini" "\
# Generated by ${SC_NAME} on $(date '+%Y-%m-%d %H:%M:%S')
# Phoebus preferences for the alarm server, passed with -settings

org.phoebus.pv/default=${EPICS_PV_DEFAULT}
org.phoebus.pv.ca/auto_addr_list=${EPICS_AUTO_ADDR_LIST}
${addr_line}

org.phoebus.applications.alarm/server=${KAFKA_HOST}:${KAFKA_PORT}
org.phoebus.applications.alarm/config_name=${ALARM_CONFIG}
"

    ## --- alarm logger -------------------------------------------------------
    ## a Spring Boot jar, nothing else is needed next to it
    install_service_jar "alarm-logger" "$(artifact 'services/alarm-logger/target/service-alarm-logger-*.jar' 'service-alarm-logger-*.jar')"
    write_root_file "${SERVICES_PREFIX}/alarm-logger/alarm_logger.properties" "\
# Generated by ${SC_NAME} on $(date '+%Y-%m-%d %H:%M:%S')
# Read by the alarm logger through -properties

server.port=${ALARM_LOGGER_PORT}
spring.main.banner-mode=off
logging.level.root=WARN

alarm_topics=${ALARM_CONFIG}
bootstrap.servers=${KAFKA_HOST}:${KAFKA_PORT}

es_host=${ES_HOST}
es_port=${ES_PORT}
es_max_size=1000
es_sniff=false
es_create_templates=true

use_dated_index_names=true
date_span_units=M
"

    ## --- alarm configuration logger ----------------------------------------
    if [[ "${WITH_CONFIG_LOGGER}" == "true" ]]; then
        install_service_jar "alarm-config-logger" "$(artifact 'services/alarm-config-logger/target/service-alarm-config-logger-*.jar' 'service-alarm-config-logger-*.jar')"
        run sudo install -d -o "${PHOEBUS_USER}" -g "${PHOEBUS_USER}" "${ALARM_CONFIG_REPO}"
        write_root_file "${SERVICES_PREFIX}/alarm-config-logger/alarm_config_logger.properties" "\
# Generated by ${SC_NAME} on $(date '+%Y-%m-%d %H:%M:%S')
# Read by the alarm configuration logger through -properties

bootstrap.servers=${KAFKA_HOST}:${KAFKA_PORT}
alarm_topics=${ALARM_CONFIG}
kafka_properties=

# Local git repository of the configuration history.
# 'remote.location' is left empty on purpose : nothing is pushed anywhere until
# a site sets it, the history stays local.
local.location=${ALARM_CONFIG_REPO}
remote.location=
use_ssh_keys=false
username=
password=
"
    fi

    ## --- save and restore ---------------------------------------------------
    ## A Spring Boot jar again, and the only service which needs Elasticsearch
    ## alone. Its properties are *added* to the ones inside the jar with
    ## spring.config.additional-location : spring.config.location would replace
    ## them and drop the index names, the context path and the LDAP settings
    ## which are not repeated here.
    if [[ "${WITH_SAVE_RESTORE}" == "true" ]]; then
        install_service_jar "save-and-restore" "$(artifact 'services/save-and-restore/target/service-save-and-restore-*.jar' 'service-save-and-restore-*.jar')"
        write_root_file "${SERVICES_PREFIX}/save-and-restore/save_restore.properties" "\
# Generated by ${SC_NAME} on $(date '+%Y-%m-%d %H:%M:%S')
# Added to the defaults of the jar with spring.config.additional-location

server.port=${SAVE_RESTORE_PORT}
server.servlet.contextPath=${SAVE_RESTORE_PATH}

elasticsearch.network.host=${ES_HOST}
elasticsearch.http.port=${ES_PORT}

logging.level.org.springframework=WARN
"
    fi

    ## --- desktop product ----------------------------------------------------
    if [[ "${WITH_DESKTOP}" == "true" ]]; then
        install_desktop_product
    fi

    run sudo chown -R "${PHOEBUS_USER}:${PHOEBUS_USER}" "${SERVICES_PREFIX}"
    install_units
}

function install_desktop_product
{
    info "installing the Phoebus desktop product into ${PHOEBUS_PREFIX}"
    run sudo install -d "${PHOEBUS_PREFIX}"

    if [[ "${DRY_RUN}" == "true" ]]; then
        info "would install the product into ${PHOEBUS_PREFIX}"
        return 0
    fi

    ## From a bundle the product arrives as the tarball Maven built, holding
    ## the jar, its lib directory and the launcher.
    if [[ -n "${BUNDLE_WORK}" ]]; then
        local ptar; ptar="$(compgen -G "${BUNDLE_WORK}/dist/product-*.tar.gz" | head -1)" \
            || die "no product tarball in the bundle, it was built with --no-desktop"
        info "installing $(basename "${ptar}")"
        run_sh "sudo rm -rf '${PHOEBUS_PREFIX}/lib' '${PHOEBUS_PREFIX}/doc'"
        ## straight into the destination, see install_service_from_bundle_tar
        run_sh "sudo tar -C '${PHOEBUS_PREFIX}' -xzf '${ptar}' --strip-components=1"
        run sudo chown -R root:root "${PHOEBUS_PREFIX}"
        run sudo chmod 0755 "${PHOEBUS_PREFIX}"
        run_sh "sudo chmod -R a+rX '${PHOEBUS_PREFIX}'"
        install_desktop_launcher
        return 0
    fi

    local target="${SRC_DIR}/phoebus-product/target"
    [[ -d "${target}" ]] || die "${target} does not exist, run './${SC_NAME} build'"
    ## product jar, its lib directory and the documentation
    run_sh "sudo cp -a '${target}'/product-*.jar '${PHOEBUS_PREFIX}/'"
    [[ -d "${target}/lib" ]] && run_sh "sudo rm -rf '${PHOEBUS_PREFIX}/lib' && sudo cp -a '${target}/lib' '${PHOEBUS_PREFIX}/'"
    [[ -d "${target}/doc" ]] && run_sh "sudo rm -rf '${PHOEBUS_PREFIX}/doc' && sudo cp -a '${target}/doc' '${PHOEBUS_PREFIX}/'"

    install_desktop_launcher
    return 0
}

## settings.ini, the launcher and the desktop entry, shared by both paths
function install_desktop_launcher
{
    ## settings for the desktop, same PV configuration as the alarm server
    local addr_line=""
    [[ -n "${EPICS_ADDR_LIST}" ]] && addr_line="org.phoebus.pv.ca/addr_list=${EPICS_ADDR_LIST}"
    ## This file is the reference settings of the installed product : phoebus.sh
    ## passes it with -settings. Only the properties this site overrides are
    ## listed, every available property and its default is in
    ## phoebus-product/settings_template.ini.
    write_root_file "${PHOEBUS_PREFIX}/settings.ini" "\
# Generated by ${SC_NAME} on $(date '+%Y-%m-%d %H:%M:%S')
#
# Settings of the installed Phoebus product, used by ${PHOEBUS_PREFIX}/phoebus.sh.
# Only the overridden properties are here, the complete list with the defaults
# is in phoebus-product/settings_template.ini of the source tree.

###################################################
# Package org.phoebus.applications.alarm
###################################################

# Kafka broker of the alarm system, the same one the alarm server publishes to
org.phoebus.applications.alarm/server=${KAFKA_HOST}:${KAFKA_PORT}
org.phoebus.applications.alarm/kafka_properties=

# The alarm configuration, i.e. the Kafka topic
org.phoebus.applications.alarm/config_name=${ALARM_CONFIG}
org.phoebus.applications.alarm/config_names=${ALARM_CONFIG}

###################################################
# Package org.phoebus.applications.alarm.logging.ui
###################################################

# REST service of the alarm logger, used by the \"Alarm Log Table\"
org.phoebus.applications.alarm.logging.ui/service_uri=http://${ES_HOST}:${ALARM_LOGGER_PORT}
org.phoebus.applications.alarm.logging.ui/results_max_size=10000

###################################################
# Package org.phoebus.applications.saveandrestore.client
###################################################

# REST service of save-and-restore
org.phoebus.applications.saveandrestore.client/jmasar.service.url=http://localhost:${SAVE_RESTORE_PORT}${SAVE_RESTORE_PATH}

###################################################
# Package org.phoebus.pv and org.phoebus.pv.ca
###################################################

org.phoebus.pv/default=${EPICS_PV_DEFAULT}
org.phoebus.pv.ca/auto_addr_list=${EPICS_AUTO_ADDR_LIST}
${addr_line}
org.phoebus.pv.ca/server_port=5064
org.phoebus.pv.ca/repeater_port=5065
"

    write_root_file "${PHOEBUS_PREFIX}/phoebus.sh" "\
#!/bin/sh
# Generated by ${SC_NAME}
TOP=\"${PHOEBUS_PREFIX}\"
JAVA_HOME=\"${JAVA_HOME_DETECTED}\"
export JAVA_HOME
PATH=\"\$JAVA_HOME/bin:\$PATH\"
export PATH
JAR=\$(echo \"\$TOP\"/product-*.jar)
exec java -jar \"\$JAR\" -settings \"\$TOP/settings.ini\" --add-modules=ALL-SYSTEM \"\$@\"
"
    run sudo chmod 755 "${PHOEBUS_PREFIX}/phoebus.sh"
    run_sh "sudo ln -sfn '${PHOEBUS_PREFIX}/phoebus.sh' /usr/local/bin/phoebus"

    write_root_file /usr/share/applications/phoebus.desktop "\
[Desktop Entry]
Type=Application
Name=Phoebus
Comment=Control System Studio Phoebus
Exec=${PHOEBUS_PREFIX}/phoebus.sh
Terminal=false
Categories=Development;Science;
"
    ok "the desktop product is installed, start it with 'phoebus'"
}

function install_units
{
    info "generating the systemd units of the alarm services"

    write_root_file /etc/systemd/system/phoebus-alarm-server.service "\
# Generated by ${SC_NAME}
[Unit]
Description=Phoebus Alarm Server (${ALARM_CONFIG})
Documentation=https://github.com/ControlSystemStudio/phoebus/tree/master/app/alarm
After=network.target kafka.service
Requires=kafka.service

[Service]
Type=simple
User=${PHOEBUS_USER}
Group=${PHOEBUS_USER}
WorkingDirectory=${SERVICES_PREFIX}/alarm-server
Environment=JAVA_HOME=${JAVA_HOME_DETECTED}
ExecStart=${JAVA_HOME_DETECTED}/bin/java -jar ${SERVICES_PREFIX}/alarm-server/alarm-server.jar \\
          -settings ${SERVICES_PREFIX}/alarm-server/alarm_settings.ini \\
          -config ${ALARM_CONFIG} \\
          -server ${KAFKA_HOST}:${KAFKA_PORT} \\
          -noshell
# a JVM stopped with SIGTERM exits 143, that is a clean shutdown
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
"

    write_root_file /etc/systemd/system/phoebus-alarm-logger.service "\
# Generated by ${SC_NAME}
[Unit]
Description=Phoebus Alarm Logger (${ALARM_CONFIG})
Documentation=https://github.com/ControlSystemStudio/phoebus/tree/master/services/alarm-logger
After=network.target kafka.service elasticsearch.service phoebus-alarm-server.service
Requires=kafka.service elasticsearch.service
# ordering only : the logger starts after the server and stops before it, but a
# failing server does not take the logger down
Wants=phoebus-alarm-server.service

[Service]
Type=simple
User=${PHOEBUS_USER}
Group=${PHOEBUS_USER}
WorkingDirectory=${SERVICES_PREFIX}/alarm-logger
Environment=JAVA_HOME=${JAVA_HOME_DETECTED}
ExecStart=${JAVA_HOME_DETECTED}/bin/java -jar ${SERVICES_PREFIX}/alarm-logger/alarm-logger.jar \\
          -properties ${SERVICES_PREFIX}/alarm-logger/alarm_logger.properties \\
          -noshell
# a JVM stopped with SIGTERM exits 143, that is a clean shutdown
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
"

    if [[ "${WITH_CONFIG_LOGGER}" == "true" ]]; then
        write_root_file /etc/systemd/system/phoebus-alarm-config-logger.service "\
# Generated by ${SC_NAME}
[Unit]
Description=Phoebus Alarm Configuration Logger (${ALARM_CONFIG})
Documentation=https://github.com/ControlSystemStudio/phoebus/tree/master/services/alarm-config-logger
After=network.target kafka.service phoebus-alarm-logger.service
Requires=kafka.service
Wants=phoebus-alarm-logger.service

[Service]
Type=simple
User=${PHOEBUS_USER}
Group=${PHOEBUS_USER}
WorkingDirectory=${SERVICES_PREFIX}/alarm-config-logger
Environment=JAVA_HOME=${JAVA_HOME_DETECTED}
ExecStart=${JAVA_HOME_DETECTED}/bin/java -Dserver.port=${ALARM_CONFIG_LOGGER_PORT} \\
          -jar ${SERVICES_PREFIX}/alarm-config-logger/alarm-config-logger.jar \\
          -properties ${SERVICES_PREFIX}/alarm-config-logger/alarm_config_logger.properties
# a JVM stopped with SIGTERM exits 143, that is a clean shutdown
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
"
    fi

    if [[ "${WITH_SAVE_RESTORE}" == "true" ]]; then
        write_root_file /etc/systemd/system/phoebus-save-and-restore.service "\
# Generated by ${SC_NAME}
[Unit]
Description=Phoebus Save and Restore service
Documentation=https://github.com/ControlSystemStudio/phoebus/tree/master/services/save-and-restore
After=network.target elasticsearch.service
Requires=elasticsearch.service

[Service]
Type=simple
User=${PHOEBUS_USER}
Group=${PHOEBUS_USER}
WorkingDirectory=${SERVICES_PREFIX}/save-and-restore
Environment=JAVA_HOME=${JAVA_HOME_DETECTED}
ExecStart=${JAVA_HOME_DETECTED}/bin/java \\
          -Dspring.config.additional-location=file:${SERVICES_PREFIX}/save-and-restore/save_restore.properties \\
          -jar ${SERVICES_PREFIX}/save-and-restore/save-and-restore.jar
# a JVM stopped with SIGTERM exits 143, that is a clean shutdown
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
"
    fi

    run sudo systemctl daemon-reload
    ok "the systemd units are installed"
}

## ----------------------------------------------------------------------------
## Kafka topics of the alarm configuration
## ----------------------------------------------------------------------------
function stage_topics
{
    banner "Stage : topics - Kafka topics of the '${ALARM_CONFIG}' configuration"

    ensure_java_known
    [[ "${DRY_RUN}" == "true" || -e /etc/systemd/system/kafka.service ]] \
        || die "kafka.service is not installed, run './${SC_NAME} kafka' first"

    ## the broker has to answer before its topics can be created
    run sudo systemctl start kafka.service
    wait_for_port "${KAFKA_HOST}" "${KAFKA_PORT}" 60 "Kafka" || die "Kafka does not listen on ${KAFKA_PORT}"

    local bin="${KAFKA_PREFIX}/bin" server="${KAFKA_HOST}:${KAFKA_PORT}" topic

    ## The alarm state topic is compacted : only the latest state of each PV is
    ## kept. The command and talk topics are deleted after a short retention.
    ## Both settings come from app/alarm/examples/create_alarm_topics.sh
    for topic in "${ALARM_CONFIG}"; do
        create_topic "${topic}"
        run_sh "sudo -u '${KAFKA_USER}' env JAVA_HOME='${JAVA_HOME_DETECTED}' '${bin}/kafka-configs.sh' \
                --bootstrap-server '${server}' --entity-type topics --alter --entity-name '${topic}' \
                --add-config cleanup.policy=compact,segment.ms=10000,min.cleanable.dirty.ratio=0.01,min.compaction.lag.ms=1000"
    done

    for topic in "${ALARM_CONFIG}Command" "${ALARM_CONFIG}Talk"; do
        create_topic "${topic}"
        run_sh "sudo -u '${KAFKA_USER}' env JAVA_HOME='${JAVA_HOME_DETECTED}' '${bin}/kafka-configs.sh' \
                --bootstrap-server '${server}' --entity-type topics --alter --entity-name '${topic}' \
                --add-config cleanup.policy=delete,segment.ms=10000,min.cleanable.dirty.ratio=0.01,min.compaction.lag.ms=1000,retention.ms=20000,delete.retention.ms=1000,file.delete.delay.ms=1000"
    done

    try_sh "sudo -u '${KAFKA_USER}' env JAVA_HOME='${JAVA_HOME_DETECTED}' '${bin}/kafka-topics.sh' --bootstrap-server '${server}' --list" || true
    ok "the topics of '${ALARM_CONFIG}' are ready"
}

function create_topic
{
    local topic="$1" bin="${KAFKA_PREFIX}/bin" server="${KAFKA_HOST}:${KAFKA_PORT}"
    if [[ "${DRY_RUN}" != "true" ]] && \
       sudo -u "${KAFKA_USER}" env JAVA_HOME="${JAVA_HOME_DETECTED}" "${bin}/kafka-topics.sh" \
            --bootstrap-server "${server}" --list 2>/dev/null | grep -qx "${topic}"; then
        ok "topic ${topic} already exists"
        return 0
    fi
    run_sh "sudo -u '${KAFKA_USER}' env JAVA_HOME='${JAVA_HOME_DETECTED}' '${bin}/kafka-topics.sh' \
            --bootstrap-server '${server}' --create --replication-factor 1 --partitions 1 --topic '${topic}'"
}

## ----------------------------------------------------------------------------
## Services
## ----------------------------------------------------------------------------
function stage_service
{
    banner "Stage : service - systemd units"

    ensure_java_known
    run sudo systemctl daemon-reload

    ## Kafka and Elasticsearch are Type=simple : systemd calls them started as
    ## soon as the process exists, long before they accept a connection. The
    ## services which depend on them try to connect at startup, so wait for the
    ## ports instead of relying on the unit ordering alone.
    local unit
    for unit in "${INFRA_UNITS[@]}"; do
        [[ -e "/etc/systemd/system/${unit}" ]] || { warn "${unit} is not installed, skipped"; continue; }
        run sudo systemctl enable "${unit}"
        run sudo systemctl restart "${unit}"
        ok "${unit} started"
    done
    [[ -e /etc/systemd/system/kafka.service ]] && \
        { wait_for_port "${KAFKA_HOST}" "${KAFKA_PORT}" 90 "Kafka" || warn "Kafka is not answering yet"; }
    [[ -e /etc/systemd/system/elasticsearch.service ]] && \
        { wait_for_port "${ES_HOST}" "${ES_PORT}" 180 "Elasticsearch" || warn "Elasticsearch is not answering yet"; }

    for unit in "${ALARM_UNITS[@]}" "${SR_UNITS[@]:-}"; do
        [[ -n "${unit}" ]] || continue
        [[ -e "/etc/systemd/system/${unit}" ]] || { warn "${unit} is not installed, skipped"; continue; }
        run sudo systemctl enable "${unit}"
        run sudo systemctl restart "${unit}"
        ok "${unit} started"
    done

    [[ "${OPEN_FIREWALL}" == "true" ]] && open_firewall
    return 0
}

function open_firewall
{
    local port
    if command -v firewall-cmd >/dev/null 2>&1 && sudo firewall-cmd --state >/dev/null 2>&1; then
        for port in "${KAFKA_PORT}" "${ES_PORT}" "${ALARM_LOGGER_PORT}"; do
            run sudo firewall-cmd --permanent --add-port="${port}/tcp"
        done
        run sudo firewall-cmd --reload
        ok "firewalld : ports ${KAFKA_PORT} ${ES_PORT} ${ALARM_LOGGER_PORT} are open"
    elif command -v ufw >/dev/null 2>&1 && sudo ufw status 2>/dev/null | grep -q "Status: active"; then
        for port in "${KAFKA_PORT}" "${ES_PORT}" "${ALARM_LOGGER_PORT}"; do
            run sudo ufw allow "${port}/tcp"
        done
        ok "ufw : the ports are open"
    else
        warn "no active firewalld or ufw was found, no port was opened"
    fi
}

## wait_for_port <host> <port> <seconds> <label>
function wait_for_port
{
    local host="$1" port="$2" limit="$3" label="$4" i=0
    [[ "${DRY_RUN}" == "true" ]] && return 0
    printf 'waiting for %s on %s:%s ' "${label}" "${host}" "${port}"
    while (( i < limit )); do
        if (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; then
            printf ' up\n'; return 0
        fi
        printf '.'; sleep 1; (( ++i ))
    done
    printf ' timeout\n'
    return 1
}

function stage_verify
{
    banner "Stage : verify - is everything answering ?"

    if [[ "${DRY_RUN}" == "true" ]]; then
        info "would wait for Kafka, Elasticsearch and the alarm logger"
        print_summary
        return 0
    fi

    wait_for_port "${KAFKA_HOST}" "${KAFKA_PORT}" 60 "Kafka" \
        && ok "Kafka answers on ${KAFKA_HOST}:${KAFKA_PORT}" \
        || warn "Kafka does not answer, check 'systemctl status kafka.service'"

    wait_for_port "${ES_HOST}" "${ES_PORT}" 120 "Elasticsearch" \
        && ok "Elasticsearch answers on ${ES_HOST}:${ES_PORT}" \
        || warn "Elasticsearch does not answer, check 'systemctl status elasticsearch.service'"

    local health
    health="$(curl -s -m 5 "http://${ES_HOST}:${ES_PORT}/_cluster/health" 2>/dev/null || true)"
    [[ -n "${health}" ]] && print_kv "cluster health" "${health:0:120}"

    wait_for_port "localhost" "${ALARM_LOGGER_PORT}" 90 "alarm logger" \
        && ok "the alarm logger REST service answers on ${ALARM_LOGGER_PORT}" \
        || warn "the alarm logger does not answer, check 'systemctl status phoebus-alarm-logger.service'"

    if [[ "${WITH_SAVE_RESTORE}" == "true" ]]; then
        wait_for_port "localhost" "${SAVE_RESTORE_PORT}" 120 "save-and-restore" \
            && ok "save-and-restore answers on http://localhost:${SAVE_RESTORE_PORT}${SAVE_RESTORE_PATH}" \
            || warn "save-and-restore does not answer, check 'systemctl status phoebus-save-and-restore.service'"
    fi

    local unit
    for unit in "${INFRA_UNITS[@]}" "${ALARM_UNITS[@]}" "${SR_UNITS[@]:-}"; do
        [[ -e "/etc/systemd/system/${unit}" ]] || continue
        [[ "$(systemctl is-active "${unit}" 2>/dev/null)" == "active" ]] \
            && print_state "${unit}" ok "active" \
            || print_state "${unit}" no "$(systemctl is-active "${unit}" 2>/dev/null || echo unknown)"
    done

    print_summary
    return 0
}

## ----------------------------------------------------------------------------
## Read only stages
## ----------------------------------------------------------------------------
function stage_paths
{
    banner "Stage : paths - where everything is"

    printf '%sBuild%s\n' "${C_BLU}" "${C_OFF}"
    print_kv "source"             "${SRC_DIR}"
    print_kv "source repository"  "${SRC_REPO} (${SRC_REF})"
    print_kv "JAVA_HOME"           "$(find_system_jdk 2>/dev/null || echo 'not found')"
    print_kv "MAVEN_HOME"          "$( [[ -x "${JAVA_ENV_PREFIX}/MAVEN/bin/mvn" ]] && echo "${JAVA_ENV_PREFIX}/MAVEN" || command -v mvn || echo 'not found')"
    print_kv "target platform"     "${SRC_DIR}/dependencies/target/lib"

    printf '\n%sAlarm services%s\n' "${C_BLU}" "${C_OFF}"
    print_kv "installation"        "${SERVICES_PREFIX}"
    print_kv "service account"     "${PHOEBUS_USER}"
    print_kv "alarm configuration" "${ALARM_CONFIG}  (Kafka topics ${ALARM_CONFIG}, ${ALARM_CONFIG}Command, ${ALARM_CONFIG}Talk)"
    print_kv "alarm server"        "${SERVICES_PREFIX}/alarm-server/alarm_settings.ini"
    print_kv "alarm logger"        "${SERVICES_PREFIX}/alarm-logger/alarm_logger.properties  (REST ${ALARM_LOGGER_PORT})"
    if [[ "${WITH_CONFIG_LOGGER}" == "true" ]]; then
        print_kv "config logger"   "${SERVICES_PREFIX}/alarm-config-logger/alarm_config_logger.properties"
        print_kv "config git repo" "${ALARM_CONFIG_REPO}"
    fi
    if [[ "${WITH_SAVE_RESTORE}" == "true" ]]; then
        printf '\n%sSave and restore%s\n' "${C_BLU}" "${C_OFF}"
        print_kv "service"         "${SERVICES_PREFIX}/save-and-restore/save_restore.properties"
        print_kv "REST"            "http://localhost:${SAVE_RESTORE_PORT}${SAVE_RESTORE_PATH}"
        print_kv "elasticsearch"   "indices saveandrestore_*"
    fi

    printf '\n%sThird party services%s\n' "${C_BLU}" "${C_OFF}"
    print_kv "Kafka"               "${KAFKA_PREFIX}  (${KAFKA_HOST}:${KAFKA_PORT}, KRaft, data ${KAFKA_DATA}, logs ${KAFKA_LOGS})"
    print_kv "Elasticsearch"       "${ES_PREFIX}  (${ES_HOST}:${ES_PORT}, data ${ES_DATA})"

    printf '\n%sDesktop%s\n' "${C_BLU}" "${C_OFF}"
    if [[ "${WITH_DESKTOP}" == "true" ]]; then
        print_kv "product"         "${PHOEBUS_PREFIX}"
        print_kv "launcher"        "/usr/local/bin/phoebus"
        print_kv "settings"        "${PHOEBUS_PREFIX}/settings.ini"
    else
        print_kv "product"         "not installed (--no-desktop)"
    fi

    printf '\n%sSystemd units%s\n' "${C_BLU}" "${C_OFF}"
    local unit
    for unit in "${INFRA_UNITS[@]}" "${ALARM_UNITS[@]}" "${SR_UNITS[@]:-}"; do
        print_kv "${unit}" "/etc/systemd/system/${unit}"
    done
    print_kv "install log" "${LOG_FILE}"
    printf '\n'
    return 0
}

function stage_exist
{
    banner "Stage : exist - what is installed"

    local jdk mvn
    jdk="$(find_system_jdk 2>/dev/null || true)"
    [[ -n "${jdk}" ]] && print_state "JDK ${JDK_MAJOR}+" ok "${jdk}" \
                      || print_state "JDK ${JDK_MAJOR}+" no "missing, run : ./${SC_NAME} java"
    mvn="$( [[ -x "${JAVA_ENV_PREFIX}/MAVEN/bin/mvn" ]] && echo "${JAVA_ENV_PREFIX}/MAVEN/bin/mvn" || command -v mvn 2>/dev/null || true)"
    [[ -n "${mvn}" ]] && print_state "Maven" ok "${mvn}" \
                      || print_state "Maven" no "missing, run : ./${SC_NAME} java"

    ## dependencies/target is wiped by the root 'clean install', so its absence
    ## says nothing : the built jars below are the meaningful check
    if [[ "${WITH_DESKTOP}" == "true" ]]; then
        compgen -G "${SRC_DIR}/phoebus-product/target/product-*.jar" >/dev/null \
            && print_state "product jar" ok "phoebus-product/target" \
            || print_state "product jar" no "not built, run : ./${SC_NAME} build"
    fi

    compgen -G "${SRC_DIR}/services/alarm-server/target/service-alarm-server-*.jar" >/dev/null \
        && print_state "built jars" ok "services/*/target/service-*.jar" \
        || print_state "built jars" no "not built, run : ./${SC_NAME} build"

    [[ -e "${KAFKA_PREFIX}/bin/kafka-server-start.sh" ]] \
        && print_state "Kafka" ok "${KAFKA_PREFIX}" \
        || print_state "Kafka" no "missing, run : ./${SC_NAME} kafka"
    [[ -e "${ES_PREFIX}/bin/elasticsearch" ]] \
        && print_state "Elasticsearch" ok "${ES_PREFIX}" \
        || print_state "Elasticsearch" no "missing, run : ./${SC_NAME} elastic"

    local name
    for name in alarm-server alarm-logger alarm-config-logger save-and-restore; do
        [[ "${name}" == "alarm-config-logger" && "${WITH_CONFIG_LOGGER}" != "true" ]] && continue
        [[ "${name}" == "save-and-restore"   && "${WITH_SAVE_RESTORE}"  != "true" ]] && continue
        [[ -e "${SERVICES_PREFIX}/${name}/${name}.jar" ]] \
            && print_state "${name}" ok "${SERVICES_PREFIX}/${name}" \
            || print_state "${name}" no "not installed, run : ./${SC_NAME} install"
    done

    if [[ "${WITH_DESKTOP}" == "true" ]]; then
        compgen -G "${PHOEBUS_PREFIX}/product-*.jar" >/dev/null \
            && print_state "desktop product" ok "${PHOEBUS_PREFIX}" \
            || print_state "desktop product" no "not installed, run : ./${SC_NAME} install"
    fi

    local unit state
    for unit in "${INFRA_UNITS[@]}" "${ALARM_UNITS[@]}" "${SR_UNITS[@]:-}"; do
        if [[ ! -e "/etc/systemd/system/${unit}" ]]; then
            print_state "${unit}" no "not installed"
            continue
        fi
        state="$(systemctl is-active "${unit}" 2>/dev/null || true)"
        [[ "${state}" == "active" ]] \
            && print_state "${unit}" ok "active ($(systemctl is-enabled "${unit}" 2>/dev/null || echo 'not enabled'))" \
            || print_state "${unit}" no "${state:-unknown}, run : ./${SC_NAME} service"
    done

    ## the two endpoints which tell whether the stack really works
    [[ "$(curl -s -m 8 -o /dev/null -w '%{http_code}' "http://${ES_HOST}:${ES_PORT}/" 2>/dev/null)" == "200" ]] \
        && print_state "elasticsearch http" ok "http://${ES_HOST}:${ES_PORT}" \
        || print_state "elasticsearch http" no "no answer"
    [[ -n "$(curl -s -m 8 "http://localhost:${ALARM_LOGGER_PORT}/" 2>/dev/null)" ]] \
        && print_state "alarm logger http" ok "http://localhost:${ALARM_LOGGER_PORT}" \
        || print_state "alarm logger http" no "no answer"
    if [[ "${WITH_SAVE_RESTORE}" == "true" ]]; then
        [[ -n "$(curl -s -m 8 "http://localhost:${SAVE_RESTORE_PORT}${SAVE_RESTORE_PATH}/" 2>/dev/null)" ]] \
            && print_state "save-and-restore http" ok "http://localhost:${SAVE_RESTORE_PORT}${SAVE_RESTORE_PATH}" \
            || print_state "save-and-restore http" no "no answer"
    fi

    printf '\n'
    return 0
}

function stage_status
{
    banner "Stage : status - systemd units"

    local unit
    for unit in "${INFRA_UNITS[@]}" "${ALARM_UNITS[@]}" "${SR_UNITS[@]:-}"; do
        [[ -e "/etc/systemd/system/${unit}" ]] || continue
        try systemctl --no-pager --full status "${unit}" || true
        printf '\n'
    done

    printf '%sKafka topics%s\n' "${C_BLU}" "${C_OFF}"
    if [[ -x "${KAFKA_PREFIX}/bin/kafka-topics.sh" ]]; then
        try_sh "'${KAFKA_PREFIX}/bin/kafka-topics.sh' --bootstrap-server '${KAFKA_HOST}:${KAFKA_PORT}' --list" \
            || warn "the broker does not answer"
    else
        warn "Kafka is not installed"
    fi
    printf '\n'
    return 0
}

function stage_uninstall
{
    banner "Stage : uninstall"

    ask_yes "stop and remove the Phoebus services and their systemd units ?" || { info "cancelled"; return 0; }

    ## stop in reverse order : the configuration logger first, the server last
    local unit i
    local all_units=("${ALARM_UNITS[@]}" "${SR_UNITS[@]:-}")
    for (( i=${#all_units[@]}-1 ; i>=0 ; i-- )); do
        unit="${all_units[i]}"
        [[ -n "${unit}" ]] || continue
        [[ -e "/etc/systemd/system/${unit}" ]] || continue
        try sudo systemctl stop "${unit}" || true
        try sudo systemctl disable "${unit}" || true
        run sudo rm -f "/etc/systemd/system/${unit}"
    done
    run sudo rm -rf "${SERVICES_PREFIX}"
    run sudo systemctl daemon-reload

    warn "Kafka and Elasticsearch are left running, their data is kept :"
    warn "    sudo systemctl disable --now kafka.service elasticsearch.service"
    warn "    sudo rm -rf ${KAFKA_PREFIX} ${ES_PREFIX} ${KAFKA_DATA} ${ES_DATA}"
    [[ "${WITH_DESKTOP}" == "true" ]] && \
        warn "the desktop product is kept : sudo rm -rf ${PHOEBUS_PREFIX} /usr/local/bin/phoebus"
    ok "the alarm services are removed"
}

## ----------------------------------------------------------------------------
## Summary
## ----------------------------------------------------------------------------
function print_summary
{
    cat <<EOF

${C_GRN}============================================================
 Phoebus alarm services : installation summary
============================================================${C_OFF}

  Alarm configuration : ${ALARM_CONFIG}
  Kafka               : ${KAFKA_HOST}:${KAFKA_PORT}   (${KAFKA_PREFIX}, KRaft)
  Elasticsearch       : http://${ES_HOST}:${ES_PORT}   (${ES_PREFIX})
  Alarm logger REST   : http://localhost:${ALARM_LOGGER_PORT}
  Save and restore    : $( [[ "${WITH_SAVE_RESTORE}" == "true" ]] && echo "http://localhost:${SAVE_RESTORE_PORT}${SAVE_RESTORE_PATH}" || echo "not installed" )
  Services            : ${SERVICES_PREFIX}
  Desktop product     : $( [[ "${WITH_DESKTOP}" == "true" ]] && echo "${PHOEBUS_PREFIX}  (run 'phoebus')" || echo "not installed" )
  Log file            : ${LOG_FILE}

  Useful commands

    ./${SC_NAME} status
    ./${SC_NAME} exist
    sudo systemctl status phoebus-alarm-server.service
    journalctl -u phoebus-alarm-server.service -f
    ${KAFKA_PREFIX}/bin/kafka-topics.sh --bootstrap-server ${KAFKA_HOST}:${KAFKA_PORT} --list
    curl 'http://${ES_HOST}:${ES_PORT}/_cat/indices?v'

  Rebuild and redeploy after a source change

    ./${SC_NAME} build install service

EOF
}

## ----------------------------------------------------------------------------
## main
## ----------------------------------------------------------------------------
function main
{
    parse_args "$@"

    : > "${LOG_FILE}" 2>/dev/null || LOG_FILE="/tmp/install_offline_phoebus.$$.log"
    log "### ${SC_NAME} started on $(date) : $*"

    banner "Phoebus : automated installation"
    info "source     : ${TOP}"
    info "stages     : ${REQUESTED_STAGES[*]}"
    info "log file   : ${LOG_FILE}"

    resolve_src
    [[ -n "${BUNDLE}" ]] && info "installing from a bundle, the source is not needed"

    detect_os
    if stages_are_read_only; then
        info "read only stages, no sudo needed"
    else
        check_sudo
        selinux_note
    fi

    trap close_bundle EXIT
    open_bundle

    local stage
    for stage in "${REQUESTED_STAGES[@]}"; do
        "stage_${stage}"
        ok "stage '${stage}' is done"
    done

    ## the summary belongs to a real installation run, not to the read only ones
    local summary="false" s
    for stage in "${REQUESTED_STAGES[@]}"; do
        for s in "${ALL_STAGES[@]}"; do
            [[ "${stage}" == "${s}" ]] && summary="true"
        done
        [[ "${stage}" == "verify" ]] && summary="false" && break
    done
    [[ "${summary}" == "true" ]] && print_summary

    ok "all the requested stages are done"
    return 0
}

main "$@"
