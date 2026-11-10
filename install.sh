#!/usr/bin/env bash
set -euo pipefail

# Цвета для красивого вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

INSTALL_DIR="/opt/bartal-bot"
SERVICE_NAME="bartal-bot"
REPO_URL="https://github.com/MBober12/bartal-bot.git"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR=""
BUILD_ROOT=""

cleanup() {
    if [ -n "$BUILD_ROOT" ] && [ -d "$BUILD_ROOT" ]; then
        rm -rf -- "$BUILD_ROOT"
    fi
}
trap cleanup EXIT

echo -e "${BLUE}====================================================${NC}"
echo -e "${BLUE}      Установка Telegram-бота (bartal-bot)         ${NC}"
echo -e "${BLUE}====================================================${NC}"

# 1. Проверка прав root
if [ "$EUID" -ne 0 ]; then
  echo -e "${RED}✗ Пожалуйста, запустите установщик с правами root (sudo).${NC}"
  exit 1
fi

# 2. Проверка и установка зависимостей (Java 17, git, Maven)
echo -e "\n${YELLOW}[1/5] Проверка окружения...${NC}"

NEED_JAVA=0
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ { split($2, v, "."); print v[1] == 1 ? v[2] : v[1]; exit }')
    if [ "${JAVA_VER:-0}" -lt 17 ]; then
        echo -e "${YELLOW}Текущая Java (${JAVA_VER:-не определена}) ниже 17. Требуется Java 17+.${NC}"
        NEED_JAVA=1
    else
        echo -e "${GREEN}✓ Java $JAVA_VER уже установлена.${NC}"
    fi
else
    NEED_JAVA=1
fi

if [ "$NEED_JAVA" -eq 1 ] || ! command -v javac >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1 || ! command -v mvn >/dev/null 2>&1; then
    echo -e "${YELLOW}Установка необходимых пакетов (OpenJDK 17, git, Maven)...${NC}"
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update -y
        apt-get install -y openjdk-17-jdk git maven
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y java-17-openjdk-devel git maven
    elif command -v yum >/dev/null 2>&1; then
        yum install -y java-17-openjdk-devel git maven
    else
        echo -e "${RED}✗ Не удалось автоматически установить пакеты. Установите JDK 17, git и Maven вручную.${NC}"
        exit 1
    fi
fi

if ! command -v java >/dev/null 2>&1 || ! command -v mvn >/dev/null 2>&1; then
    echo -e "${RED}✗ Java или Maven не найдены после установки.${NC}"
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ { split($2, v, "."); print v[1] == 1 ? v[2] : v[1]; exit }')
if [ "${JAVA_VER:-0}" -lt 17 ]; then
    echo -e "${RED}✗ После установки используется Java ${JAVA_VER}. Переключите java на JDK 17.${NC}"
    exit 1
fi

# 3. Запрашиваем токен до сборки. Ввод не отображается в терминале.
echo -e "\n${YELLOW}[2/5] Настройка токена Telegram...${NC}"
read -r -s -p "Введите токен Telegram-бота: " BOT_TOKEN
echo
if [ -z "$BOT_TOKEN" ]; then
    echo -e "${RED}✗ Токен не может быть пустым.${NC}"
    exit 1
fi

while true; do
    read -r -p "Введите числовой Telegram ID первого администратора: " ADMIN_CHAT_ID
    if [[ "$ADMIN_CHAT_ID" =~ ^[1-9][0-9]{0,17}$ ]] || { [[ "$ADMIN_CHAT_ID" =~ ^[1-9][0-9]{18}$ ]] && [[ "$ADMIN_CHAT_ID" < "9223372036854775807" || "$ADMIN_CHAT_ID" == "9223372036854775807" ]]; }; then
        break
    fi
    echo -e "${RED}✗ Введите положительный числовой Telegram ID без пробелов.${NC}"
done

# 4. Подготовка директории и сборка
# Используем каталог скрипта, поэтому запуск работает из любого текущего каталога.
echo -e "\n${YELLOW}[3/5] Подготовка файлов и сборка бота...${NC}"
mkdir -p "$INSTALL_DIR"

if [ -f "$SCRIPT_DIR/pom.xml" ] && [ -d "$SCRIPT_DIR/src" ]; then
    SOURCE_DIR="$SCRIPT_DIR"
    echo -e "${GREEN}✓ Исходники найдены: $SOURCE_DIR${NC}"
else
    echo -e "${GREEN}✓ Клонирование репозитория...${NC}"
    BUILD_ROOT="$(mktemp -d /tmp/bartal-bot-build.XXXXXX)"
    git clone "$REPO_URL" "$BUILD_ROOT/source"
    SOURCE_DIR="$BUILD_ROOT/source"
fi

cd "$SOURCE_DIR"
mvn clean package -DskipTests
cp target/bartal-bot.jar "$INSTALL_DIR/bartal-bot.jar"
cp "$SOURCE_DIR/bartal-bot.service" "/etc/systemd/system/${SERVICE_NAME}.service"

# Копируем служебные скрипты в каталог установки (проверяем как в scripts/, так и в корне)
mkdir -p "$INSTALL_DIR/scripts"
for s in add-admin.sh remove-admin.sh uninstall.sh; do
    if [ -f "$SOURCE_DIR/scripts/$s" ]; then
        cp "$SOURCE_DIR/scripts/$s" "$INSTALL_DIR/scripts/$s"
        cp "$SOURCE_DIR/scripts/$s" "$INSTALL_DIR/$s"
        chmod +x "$INSTALL_DIR/scripts/$s" "$INSTALL_DIR/$s"
    elif [ -f "$SOURCE_DIR/$s" ]; then
        cp "$SOURCE_DIR/$s" "$INSTALL_DIR/$s"
        chmod +x "$INSTALL_DIR/$s"
    fi
done

# 5. Создание/обновление конфигурации. Остальные параметры сохраняются.
echo -e "\n${YELLOW}[4/5] Сохранение конфигурации...${NC}"
CONFIG_FILE="$INSTALL_DIR/config.properties"
CONFIG_TMP="$(mktemp "${CONFIG_FILE}.tmp.XXXXXX")"
chmod 600 "$CONFIG_TMP"

if [ -f "$CONFIG_FILE" ]; then
    BOT_TOKEN="$BOT_TOKEN" ADMIN_CHAT_ID="$ADMIN_CHAT_ID" awk '
        BEGIN { token_replaced = 0; admin_replaced = 0 }
        /^bot\.token=/ {
            print "bot.token=" ENVIRON["BOT_TOKEN"]
            token_replaced = 1
            next
        }
        /^admin\.chatId=/ {
            print "admin.chatId=" ENVIRON["ADMIN_CHAT_ID"]
            admin_replaced = 1
            next
        }
        { print }
        END {
            if (!token_replaced) print "bot.token=" ENVIRON["BOT_TOKEN"]
            if (!admin_replaced) print "admin.chatId=" ENVIRON["ADMIN_CHAT_ID"]
        }
    ' "$CONFIG_FILE" > "$CONFIG_TMP"
else
    cat > "$CONFIG_TMP" <<CFG
bot.token=$BOT_TOKEN
admin.chatId=$ADMIN_CHAT_ID
db.path=bartal-bot.db
projects.file=projects.xlsx
CFG
fi
mv -f -- "$CONFIG_TMP" "$CONFIG_FILE"
unset BOT_TOKEN ADMIN_CHAT_ID

echo -e "${GREEN}✓ Конфигурация сохранена с правами 600.${NC}"

# 6. Регистрация и запуск systemd службы
echo -e "\n${YELLOW}[5/5] Регистрация systemd службы...${NC}"
JAVA_PATH="$(command -v java)"

cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<SERVICE
[Unit]
Description=Bartal Telegram Bot Service
After=network.target

[Service]
Type=simple
WorkingDirectory=${INSTALL_DIR}
ExecStart=${JAVA_PATH} -jar ${INSTALL_DIR}/bartal-bot.jar
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
SERVICE

systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"

sleep 2
if systemctl is-active --quiet "$SERVICE_NAME"; then
    echo -e "${GREEN}====================================================${NC}"
    echo -e "${GREEN}✓ Бот успешно установлен и запущен!                 ${NC}"
    echo -e "${GREEN}====================================================${NC}"
else
    echo -e "${RED}✗ Служба запустилась с ошибкой. Проверьте логи:${NC}"
    journalctl -u "$SERVICE_NAME" -n 20 --no-pager
    exit 1
fi

echo -e "\n${BLUE}Полезные команды:${NC}"
echo -e "  Статус службы:   ${YELLOW}systemctl status ${SERVICE_NAME}${NC}"
echo -e "  Смотреть логи:   ${YELLOW}journalctl -u ${SERVICE_NAME} -f${NC}"
echo -e "  Перезапуск:      ${YELLOW}systemctl restart ${SERVICE_NAME}${NC}"
echo -e "  Остановить:      ${YELLOW}systemctl stop ${SERVICE_NAME}${NC}"
echo -e "  Папка бота:      ${YELLOW}${INSTALL_DIR}${NC}"
echo -e "  Добавить админа: ${YELLOW}${INSTALL_DIR}/scripts/add-admin.sh${NC}"
echo -e "  Снять админа:    ${YELLOW}${INSTALL_DIR}/scripts/remove-admin.sh${NC}"
echo -e "  Деинсталляция:   ${YELLOW}sudo ${INSTALL_DIR}/scripts/uninstall.sh${NC}\n"
