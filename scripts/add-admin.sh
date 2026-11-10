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
    echo -e "${YELLOW}Убедитесь, что бот хотя бы один раз запускался для создания БД.${NC}"
    exit 1
fi

if ! command -v sqlite3 >/dev/null 2>&1; then
    echo -e "${RED}✗ Для работы скрипта требуется sqlite3. Установите его: apt install sqlite3${NC}"
    exit 1
fi

echo -e "${BLUE}====================================================${NC}"
echo -e "${BLUE}        Добавление / Назначение Администратора       ${NC}"
echo -e "${BLUE}====================================================${NC}"

TARGET_ID="${1:-}"
TARGET_NAME="${2:-}"

if [ -z "$TARGET_ID" ]; then
    read -r -p "Введите Telegram ID нового администратора: " TARGET_ID
fi

if ! [[ "$TARGET_ID" =~ ^[1-9][0-9]{0,18}$ ]]; then
    echo -e "${RED}✗ Некорректный Telegram ID. ID должен содержать только цифры.${NC}"
    exit 1
fi

# Проверяем, есть ли уже пользователь с таким chatId
EXISTING_NAME=$(sqlite3 "$DB_PATH" "SELECT name FROM users WHERE chatId='$TARGET_ID' LIMIT 1;")

if [ -n "$EXISTING_NAME" ]; then
    echo -e "Пользователь найден в БД: ${YELLOW}$EXISTING_NAME${NC} (ID: $TARGET_ID)"
    if [ -z "$TARGET_NAME" ]; then
        read -r -p "Оставить имя '$EXISTING_NAME' или ввести новое? (Enter чтобы оставить): " NEW_NAME_INPUT
        if [ -n "$NEW_NAME_INPUT" ]; then
            TARGET_NAME="$NEW_NAME_INPUT"
        else
            TARGET_NAME="$EXISTING_NAME"
        fi
    fi
    # Назначаем права администратора и пользователя
    sqlite3 "$DB_PATH" "UPDATE users SET name='$TARGET_NAME', isAdmin=1, isUser=1 WHERE chatId='$TARGET_ID';"
    echo -e "${GREEN}✓ Пользователю '$TARGET_NAME' (ID: $TARGET_ID) успешно выданы права администратора и пользователя.${NC}"
else
    if [ -z "$TARGET_NAME" ]; then
        read -r -p "Введите имя/псевдоним для администратора: " TARGET_NAME
    fi
    if [ -z "$TARGET_NAME" ]; then
        TARGET_NAME="Администратор"
    fi
    # Создаем нового пользователя
    sqlite3 "$DB_PATH" "INSERT INTO users (chatId, name, state, lastMessage, mesIdsAndDocIds, showInfo, isUser, isAdmin, isAcceptor, isReceiver, isRegistrar) \
                        VALUES ('$TARGET_ID', '$TARGET_NAME', '', '', '', 0, 1, 1, 0, 0, 0);"
    echo -e "${GREEN}✓ Администратор '$TARGET_NAME' (ID: $TARGET_ID) успешно создан с ролями администратора и пользователя.${NC}"
fi
