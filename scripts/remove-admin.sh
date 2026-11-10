#!/usr/bin/env bash
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

# Определяем путь к config.properties и базе данных
CONFIG_FILE="config.properties"
if [ ! -f "$CONFIG_FILE" ]; then
    if [ -f "$SCRIPT_DIR/config.properties" ]; then
        CONFIG_FILE="$SCRIPT_DIR/config.properties"
    elif [ -f "$SCRIPT_DIR/../config.properties" ]; then
        CONFIG_FILE="$SCRIPT_DIR/../config.properties"
    elif [ -f "/opt/bartal-bot/config.properties" ]; then
        CONFIG_FILE="/opt/bartal-bot/config.properties"
    fi
fi

DB_PATH="bartal-bot.db"
if [ -f "$CONFIG_FILE" ]; then
    CFG_DB=$(grep -E '^db\.path=' "$CONFIG_FILE" | cut -d'=' -f2- | tr -d '\r')
    if [ -n "$CFG_DB" ]; then
        if [[ "$CFG_DB" = /* ]]; then
            DB_PATH="$CFG_DB"
        else
            CFG_DIR="$(dirname "$CONFIG_FILE")"
            DB_PATH="$CFG_DIR/$CFG_DB"
        fi
    fi
fi

if [ ! -f "$DB_PATH" ]; then
    echo -e "${RED}✗ База данных не найдена: $DB_PATH${NC}"
    exit 1
fi

if ! command -v sqlite3 >/dev/null 2>&1; then
    echo -e "${RED}✗ Для работы скрипта требуется sqlite3. Установите его: apt install sqlite3${NC}"
    exit 1
fi

echo -e "${BLUE}====================================================${NC}"
echo -e "${BLUE}          Снятие роли Администратора                 ${NC}"
echo -e "${BLUE}====================================================${NC}"

# Показываем текущий список администраторов
echo -e "Текущие администраторы в БД:"
sqlite3 "$DB_PATH" -header -column "SELECT chatId, name, isAdmin FROM users WHERE isAdmin=1;"
echo

ADMIN_COUNT=$(sqlite3 "$DB_PATH" "SELECT count(*) FROM users WHERE isAdmin=1;")
if [ "$ADMIN_COUNT" -le 1 ]; then
    echo -e "${YELLOW}Внимание: в системе остался всего 1 администратор (${ADMIN_COUNT}). Снятие прав невозможно без назначения другого.${NC}"
    exit 1
fi

TARGET_ID="${1:-}"
if [ -z "$TARGET_ID" ]; then
    read -r -p "Введите Telegram ID администратора, которого хотите снять с должности: " TARGET_ID
fi

if ! [[ "$TARGET_ID" =~ ^[1-9][0-9]{0,18}$ ]]; then
    echo -e "${RED}✗ Некорректный Telegram ID. ID должен содержать только цифры.${NC}"
    exit 1
fi

IS_ADMIN=$(sqlite3 "$DB_PATH" "SELECT isAdmin FROM users WHERE chatId='$TARGET_ID';")

if [ "$IS_ADMIN" != "1" ]; then
    echo -e "${RED}✗ Пользователь с ID $TARGET_ID не является администратором.${NC}"
    exit 1
fi

sqlite3 "$DB_PATH" "UPDATE users SET isAdmin=0 WHERE chatId='$TARGET_ID';"
USER_NAME=$(sqlite3 "$DB_PATH" "SELECT name FROM users WHERE chatId='$TARGET_ID';")
echo -e "${GREEN}✓ С пользователя '$USER_NAME' (ID: $TARGET_ID) успешно сняты права администратора.${NC}"
