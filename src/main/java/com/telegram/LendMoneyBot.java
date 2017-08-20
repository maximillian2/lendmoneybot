/*
 * MIT License
 *
 * Copyright (c) 2017 Maksym Tymoshyk
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.telegram;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.log4j.Log4j;
import org.telegram.telegrambots.api.methods.AnswerInlineQuery;
import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.api.objects.Contact;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.api.objects.User;
import org.telegram.telegrambots.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.exceptions.TelegramApiException;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Log4j
public class LendMoneyBot extends TelegramLongPollingBot {
  private static boolean addLenderStatus = false;
  private static boolean addLendSum = false;

  private static boolean editUserStart = false;
  private static boolean editUserAction = false, decreaseDebt = false;
  private static boolean editUserSum = false;

  private PersistenceService persistenceService;
  private Jedis redisDb;
  private Gson serializer;
  private ReplyKeyboardMarkup mainKeyboardMarkup;

  LendMoneyBot() {
    persistenceService = new PersistenceService();
    redisDb = new Jedis();
    serializer = new GsonBuilder().create();

    // Setup bot keyboard layout by rows
    KeyboardRow firstRow = new KeyboardRow();
    firstRow.add("Add lender");
    KeyboardRow secondRow = new KeyboardRow();
    Arrays.asList("Show info", "Edit lender info", "Help").forEach(secondRow::add);
    mainKeyboardMarkup = new ReplyKeyboardMarkup().setKeyboard(Arrays.asList(firstRow, secondRow));
  }

  @Override
  public void onUpdateReceived(Update update) {
    /* Inline mode */
    if (update.hasInlineQuery()) {
      log.info("Query inlined: " + update.getInlineQuery().getQuery());
      if (update.getInlineQuery().getQuery().equals("me")) {
        log.info("ID: " + update.getInlineQuery().getId());
        log.info("Query: " + update.getInlineQuery().getQuery());

        AnswerInlineQuery answerInlineQuery = new AnswerInlineQuery()
            .setResults(persistenceService.findInlineInfoWithUser(update.getInlineQuery().getFrom()))
            .setInlineQueryId(update.getInlineQuery().getId());

        try {
          answerInlineQuery(answerInlineQuery);
        } catch (TelegramApiException e) {
          e.printStackTrace();
          log.error(e.getMessage());
        }
      }

    /* PM mode */
    } else if (update.hasMessage()) {
      try {
        // Aliases used in this block
        Long chatId = update.getMessage().getChatId();
        boolean updateHasText = update.getMessage().hasText();
        String updateMessage = update.getMessage().getText();
        User currentUser = update.getMessage().getFrom();
        String redisUserString = "user/" + currentUser.getId();

        // Initialize bot
        if (updateHasText && updateMessage.equals("/start")) {
          String greetingText = "Welcome to LendMoneyBot!\nUse /add to start adding or /help to get things clear.";
          SendMessage startSendMessage = new SendMessage().setChatId(chatId).setText(greetingText);
          startSendMessage.setReplyMarkup(mainKeyboardMarkup);

          sendMessage(startSendMessage);

          // Add new lender candidate action
        } else if (updateHasText && (updateMessage.equals("/add") || updateMessage.equals("Add lender"))) {
          String addLenderMessage = "Send lender contact to this bot to proceed.";
          sendMessage(new SendMessage().setChatId(chatId).setText(addLenderMessage));
          addLenderStatus = true;

          // Getting contact from user
        } else if (addLenderStatus) {
          if (update.getMessage().getContact() != null) {
            redisDb.set(redisUserString + "/add_contact_value", serializer.toJson(update.getMessage().getContact()));
            String lendSumMessage = "Got it. How much do you own to this person? :)";
            sendMessage(new SendMessage().setChatId(chatId).setText(lendSumMessage));
            addLenderStatus = false;
            addLendSum = true;
          } else {
            sendMessage(new SendMessage().setChatId(chatId).setText("Invalid contact received. Try again!"));
          }

          // Getting sum information from user
        } else if (addLendSum && updateHasText) {
          String resultMessage;
          try {
            Double lendSum = Double.parseDouble(updateMessage);
            if (lendSum <= 0) {
              throw new Exception("Sum must be greater than 0.");
            }

            Contact deserializeContact = serializer.fromJson(redisDb.get(redisUserString + "/add_contact_value"), Contact.class);
            boolean isTransactionSuccess = persistenceService.addLenderTo(deserializeContact, lendSum, currentUser.getId());

            if (isTransactionSuccess) {
              resultMessage = "Successfully added lender. You can now track info by using /show command";
            } else {
              resultMessage = "Error adding lender. :(";
            }
            addLendSum = false;
          } catch (NumberFormatException e) {
            log.error(e.getMessage());
            e.printStackTrace();
            resultMessage = "Error while getting sum number. Try again :D";
          } catch (Exception e) {
            log.error(e.getMessage());
            resultMessage = e.getMessage();
          }
          sendMessage(new SendMessage().setChatId(chatId).setText(resultMessage));

          // Show information about all users connected with current user
        } else if (updateHasText && (updateMessage.equals("/show") || updateMessage.equals("Show info"))) {
          List<LendUser> allUserInformation = persistenceService.getAllUsersInformation(currentUser);
          if (allUserInformation.isEmpty()) {
            sendMessage(new SendMessage().setChatId(chatId).setText("You don't have any debts. Congrats! :)"));
          } else {
            sendMessage(new SendMessage()
                .setChatId(chatId)
                .setText(
                    String.join("\n", allUserInformation.stream().map(LendUser::toString).collect(Collectors.toCollection(ArrayList::new)))
                )
            );
          }

          // Lend info edit process
        } else if (updateHasText && (updateMessage.equals("/edit") || updateMessage.equals("Edit lender info"))) {
          if (persistenceService.getAmountOfUsers(currentUser) > 0) {
            StringBuilder editMessageBuilder = new StringBuilder().append("Select user: \n");
            List<LendUser> allUserInformation = persistenceService.getAllUsersInformation(currentUser);
            for (int i = 0; i < allUserInformation.size(); i++) {
              editMessageBuilder.append("/").append(i + 1).append(" ").append(allUserInformation.get(i).toString()).append("\n");
            }

            sendMessage(new SendMessage().setChatId(chatId).setText(editMessageBuilder.toString()));
            editUserStart = true;
          } else {
            sendMessage(new SendMessage().setChatId(chatId).setText("No users to edit. :)"));
          }
        } else if (editUserStart && updateHasText) {
          List<LendUser> allUserInformation = persistenceService.getAllUsersInformation(currentUser);

          if (updateMessage.matches("(/)?[0-9]+")) {
            int userNumber = Integer.parseInt(updateMessage.replace("/", "").trim()) - 1;
            redisDb.set(redisUserString + "/edit_user_id", String.valueOf(allUserInformation.get(userNumber).getUserId()));

            if (userNumber >= allUserInformation.size()) {
              sendMessage(new SendMessage().setChatId(chatId).setText("Number entered is too big to be correct. Try again!"));
            } else {
              ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();
              KeyboardRow firstRow = new KeyboardRow();
              Arrays.asList("Increase", "Decrease").forEach(firstRow::add);
              replyKeyboardMarkup.setKeyboard(Collections.singletonList(firstRow));

              sendMessage(new SendMessage().setReplyMarkup(replyKeyboardMarkup).setChatId(chatId).setText("/1 Increase debt or /2 decrease ?"));
              editUserStart = false;
              editUserAction = true;
            }
          } else {
            sendMessage(new SendMessage().setChatId(chatId).setText("User number format not correct."));
          }

        } else if (editUserAction && updateHasText) {
          if (updateMessage.matches("(\\/)?[1-2](\\s)?|(Increase|Decrease)")) {
            if (updateMessage.matches("(Increase|Decrease)")) {
              updateMessage = (updateMessage.equals("Increase")) ? "1" : "2"; // fixme
            }

            int userAction = Integer.parseInt(updateMessage.replace("/", "").trim());

            sendMessage(new SendMessage().setChatId(chatId).setReplyMarkup(mainKeyboardMarkup).setText("Enter sum to " + ((userAction == 1) ? "increase" : "decrease")));
            editUserAction = false;
            editUserSum = true;
            decreaseDebt = (userAction == 2);
          } else {
            sendMessage(new SendMessage().setChatId(chatId).setText("User number format not correct"));
          }
        } else if (editUserSum && updateHasText) {
          try {
            double editLendSum = Double.parseDouble(updateMessage);
            if (editLendSum <= 0) {
              throw new Exception("Sum should be greater than 0");
            }

            boolean result = persistenceService.editUser(currentUser.getId(), Integer.parseInt(redisDb.get(redisUserString + "/edit_user_id")), decreaseDebt, editLendSum);

            sendMessage(new SendMessage().setChatId(chatId).setText(result ? "Successfully edited user! :)" : "Error editing user. :("));
            editUserSum = false;
          } catch (NumberFormatException e) {
            log.error(e.getMessage());
            e.printStackTrace();

            sendMessage(new SendMessage().setChatId(chatId).setText("Error while getting sum number. Try again :D"));
          } catch (Exception e) {
            log.error(e.getMessage());
            e.printStackTrace();

            sendMessage(new SendMessage().setChatId(chatId).setText(e.getMessage()));
          }
          // Simple help information with possible commands
        } else if (updateHasText && (updateMessage.equals("/help") || updateMessage.equals("Help"))) {
          StringBuilder helpBuilder = new StringBuilder();
          helpBuilder
              .append("I can help you to track information about your debts to other people on Telegram.\n")
              .append("\nList of available commands:\n")
              .append("/start - initiate bot, reset all current data\n")
              .append("/add - add new person on debt list\n")
              .append("/show - print out all people on the list\n")
              .append("/edit - change information about person on the list\n")
              .append("/help - print this message\n")
              .append("/about - write any bugs, wishes and feedback here\n");

          sendMessage(new SendMessage().setChatId(chatId).setText(helpBuilder.toString()));
        } else if (updateHasText && updateMessage.equals("/about")) {
          StringBuilder aboutStringBuilder = new StringBuilder();
          aboutStringBuilder
              .append("Developer: @maksym_tymoshyk\n");
//              .append("Icons: icon authors");
          sendMessage(new SendMessage().setChatId(chatId).setText(aboutStringBuilder.toString()));
        } else {
          sendMessage(new SendMessage().setChatId(chatId).setText("Unknown command. Use '/help' or custom keyboard to show available commands."));
        }
      } catch (TelegramApiException e) {
        e.printStackTrace();
        log.error(e.getMessage());
      }
    }
  }

  @Override
  public String getBotUsername() {
    return BuildVars.BOT_NAME;
  }

  @Override
  public String getBotToken() {
    return BuildVars.BOT_API_KEY;
  }
}
