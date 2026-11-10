package bot.handler;

import bot.model.BotTask;
import bot.model.DocResult;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class BirthdayHandler {
    private static final Random random = new Random();

    private static final String[][] BIRTHDAYS = {
        {"427232851", "13.02", "1986", "Александр, эт самое, с %d-летием вас!", "Пап, с днем рождения!", "Александр Игоревич, с днюхой вас"},
        {"550880743", "9.06", "1985", "Александр, с %d-летием !", "🎉Дядь Саш, с днем рождения!!!🎉", "Александр Сергеевич, с днюхой вас"},
        {"1266781592", "10.06", "1981", "Артем, с %d-летием !", "🎉Артем, с днем рождения!!!🎉", "Уважаемый работодатель, с днюхой вас", "Это тебе за масленицу, с днем рождения)"},
    };

    private static final String[][] COMPANY_MESSAGES = {
        {"27.11", "2015", "Поздравляю вас с %d-летием ГК Бартал!", "С др, Бартал", "Счет на оплату праздничного корпоратива тоже можно передать через бота..."},
    };

    public DocResult check(long chatId) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("d.MM"));
        int year = LocalDate.now().getYear();

        // День рождения компании
        for (String[] cm : COMPANY_MESSAGES) {
            if (today.equals(cm[0])) {
                for (int i = 0; i < BIRTHDAYS.length; i++) {
                    if (String.valueOf(chatId).equals(BIRTHDAYS[i][0])) {
                        int age = year - Integer.parseInt(cm[1]);
                        String[] messages = new String[cm.length - 2];
                        System.arraycopy(cm, 2, messages, 0, messages.length);
                        String msg = messages[random.nextInt(messages.length)];
                        if (msg.contains("%d")) msg = String.format(msg, age);
                        return textResult(chatId, msg);
                    }
                }
            }
        }

        // Дни рождения сотрудников
        for (String[] bd : BIRTHDAYS) {
            if (String.valueOf(chatId).equals(bd[0]) && today.equals(bd[1])) {
                int age = year - Integer.parseInt(bd[2]);
                String[] messages = new String[bd.length - 3];
                System.arraycopy(bd, 3, messages, 0, messages.length);
                String msg = messages[random.nextInt(messages.length)];
                if (msg.contains("%d")) msg = String.format(msg, age);
                return textResult(chatId, msg);
            }
        }

        return null;
    }

    private DocResult textResult(long chatId, String text) {
        DocResult result = new DocResult();
        result.addTask(new BotTask(BotTask.Type.SEND_TEXT, chatId, text, null, null));
        return result;
    }
}
