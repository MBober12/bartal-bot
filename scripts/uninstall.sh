#!/usr/bin/env bash
set -euo pipefail

# Цвета для красивого вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

INSTALL_DIR="/opt/bartal-bot"
SERVICE_NAME="bartal-bot"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

MODE="${1:-}"

# Функция справки
show_help() {
    echo -e "${BLUE}====================================================${NC}"
    echo -e "${BLUE}    Деинсталляция Telegram-бота (bartal-bot)        ${NC}"
    echo -e "${BLUE}====================================================${NC}"
    echo -e "Использование: sudo $0 [ОПЦИЯ]"
    echo -e "\nОпции:"
    echo -e "  --purge, -f       Полное удаление (служба + каталог /opt/bartal-bot с БД и конфигом)"
    echo -e "  --keep-data       Удалить службу и JAR, но сохранить базу данных и конфигурацию"
    echo -e "  --backup          Создать архив бэкапа перед полным удалением каталога"
    echo -e "  --help, -h        Показать эту справку"
    echo -e "  (без параметров)  Интерактивный режим с выбором действия\n"
}

if [ "$MODE" = "--help" ] || [ "$MODE" = "-h" ]; then
    show_help
    exit 0
fi

echo -e "${BLUE}====================================================${NC}"
echo -e "${BLUE}    Деинсталляция Telegram-бота (bartal-bot)        ${NC}"
echo -e "${BLUE}====================================================${NC}"

# 1. Проверка прав root
if [ "$EUID" -ne 0 ]; then
  echo -e "${RED}✗ Пожалуйста, запустите деинсталлятор с правами root (sudo).${NC}"
  exit 1
fi

# 2. Остановка и отключение systemd службы
echo -e "\n${YELLOW}[1/3] Остановка и отключение службы ${SERVICE_NAME}...${NC}"

if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
    echo -e "  Остановка службы ${SERVICE_NAME}..."
    systemctl stop "$SERVICE_NAME" || true
    echo -e "${GREEN}✓ Служба остановлена.${NC}"
else
    echo -e "  Служба ${SERVICE_NAME} уже остановлена или не запущена."
fi

if systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; then
    echo -e "  Отключение автозапуска службы ${SERVICE_NAME}..."
    systemctl disable "$SERVICE_NAME" || true
    echo -e "${GREEN}✓ Автозапуск отключен.${NC}"
fi

# 3. Удаление systemd service unit
echo -e "\n${YELLOW}[2/3] Удаление systemd service unit...${NC}"
if [ -f "$SERVICE_FILE" ]; then
    rm -f "$SERVICE_FILE"
    systemctl daemon-reload
    systemctl reset-failed 2>/dev/null || true
    echo -e "${GREEN}✓ Файл службы ${SERVICE_FILE} удален.${NC}"
else
    echo -e "  Файл ${SERVICE_FILE} не найден (пропущено)."
fi

# 4. Обработка каталога установки
echo -e "\n${YELLOW}[3/3] Обработка файлов приложения (${INSTALL_DIR})...${NC}"

ACTION=""
if [ "$MODE" = "--purge" ] || [ "$MODE" = "-f" ]; then
    ACTION="purge"
elif [ "$MODE" = "--keep-data" ]; then
    ACTION="keep"
elif [ "$MODE" = "--backup" ]; then
    ACTION="backup"
fi

if [ -d "$INSTALL_DIR" ]; then
    if [ -z "$ACTION" ]; then
        echo -e "${CYAN}Каталог ${INSTALL_DIR} существует.${NC}"
        echo -e "Выберите действие:"
        echo -e "  ${YELLOW}1)${NC} Удалить всё полностью (включая БД, конфигурацию и логи)"
        echo -e "  ${YELLOW}2)${NC} Сохранить данные (удалить только JAR и службу, оставить базу данных и config.properties)"
        echo -e "  ${YELLOW}3)${NC} Создать архив резервной копии и удалить каталог полностью"
        echo -e "  ${YELLOW}4)${NC} Не трогать каталог ${INSTALL_DIR}"
        
        read -r -p "Ваш выбор [1-4] (по умолчанию 2): " USER_CHOICE
        case "${USER_CHOICE:-2}" in
            1) ACTION="purge" ;;
            2) ACTION="keep" ;;
            3) ACTION="backup" ;;
            4) ACTION="skip" ;;
            *) 
               echo -e "${YELLOW}Неверный ввод, выбран безопасный вариант: сохранить данные.${NC}"
               ACTION="keep"
               ;;
        esac
    fi

    case "$ACTION" in
        backup)
            TIMESTAMP="$(date +'%Y%m%d_%H%M%S')"
            BACKUP_FILE="/opt/bartal-bot-backup-${TIMESTAMP}.tar.gz"
            echo -e "  Создание архива ${BACKUP_FILE}..."
            tar -czf "$BACKUP_FILE" -C "/opt" "bartal-bot"
            echo -e "${GREEN}✓ Бэкап сохранен: ${BACKUP_FILE}${NC}"
            echo -e "  Удаление каталога ${INSTALL_DIR}..."
            rm -rf "$INSTALL_DIR"
            echo -e "${GREEN}✓ Каталог ${INSTALL_DIR} удален.${NC}"
            ;;
        purge)
            echo -e "  Полное удаление каталога ${INSTALL_DIR}..."
            rm -rf "$INSTALL_DIR"
            echo -e "${GREEN}✓ Каталог ${INSTALL_DIR} со всеми данными удален.${NC}"
            ;;
        keep)
            echo -e "  Очистка исполняемых файлов (JAR, скрипты) с сохранением БД и настроек..."
            rm -f "$INSTALL_DIR/bartal-bot.jar"
            rm -f "$INSTALL_DIR"/*.tmp* 2>/dev/null || true
            echo -e "${GREEN}✓ JAR-файл удален. База данных и конфиг сохранены в ${INSTALL_DIR}.${NC}"
            ;;
        skip)
            echo -e "  Каталог ${INSTALL_DIR} оставлен без изменений."
            ;;
    esac
else
    echo -e "  Каталог ${INSTALL_DIR} не найден (пропущено)."
fi

echo -e "\n${GREEN}====================================================${NC}"
echo -e "${GREEN}✓ Деинсталляция bartal-bot успешно завершена!       ${NC}"
echo -e "${GREEN}====================================================${NC}\n"
