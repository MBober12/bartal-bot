package bot.scheduler;

import bot.Bot;
import bot.model.BotTask;
import bot.repository.ReminderRepository;
import bot.repository.ReminderRepository.ReminderEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class Reminder {

    private static final Logger log = LoggerFactory.getLogger(Reminder.class);

    private final ReminderRepository reminderRepo;
    private final Bot bot;

    public Reminder(ReminderRepository reminderRepo, Bot bot) {
        this.reminderRepo = reminderRepo;
        this.bot = bot;
    }

    public void run() {
        List<ReminderEntry> entries = reminderRepo.findAll();
        java.time.LocalTime now = java.time.LocalTime.now();

        if (now.getHour() < 6) {
            return;
        }

        for (ReminderEntry entry : entries) {
            try {
                if (entry.remindsPassed >= 3 && now.getMinute() != 0) {
                    continue;
                }

                String text = "Вы ранее начали " + entry.stage + " этого документа, но так и не завершили. " +
                        "Пожалуйста, примите решение по документу, либо верните его обратно.";

                BotTask task = new BotTask(BotTask.Type.SEND_TEXT, Long.parseLong(entry.chatId), text, null, null);
                task.setReplyToMessageId(entry.messageId);
                bot.executeTask(task);

                reminderRepo.incrementRemindsPassed(entry.chatId, entry.messageId);

            } catch (Exception e) {
                log.error("Ошибка напоминания для chatId={}: {}", entry.chatId, e.getMessage(), e);
            }
        }
    }

    public static long getDelay() {
        java.time.LocalTime now = java.time.LocalTime.now();
        long delay;
        if (now.getMinute() > 30) {
            delay = 60 - now.getMinute();
        } else {
            delay = 30 - now.getMinute();
        }
        return delay;
    }
}
