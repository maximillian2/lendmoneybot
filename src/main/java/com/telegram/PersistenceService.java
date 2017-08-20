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

import lombok.extern.log4j.Log4j;
import org.apache.commons.dbutils.DbUtils;
import org.telegram.telegrambots.api.objects.Contact;
import org.telegram.telegrambots.api.objects.User;
import org.telegram.telegrambots.api.objects.inlinequery.inputmessagecontent.InputTextMessageContent;
import org.telegram.telegrambots.api.objects.inlinequery.result.InlineQueryResult;
import org.telegram.telegrambots.api.objects.inlinequery.result.InlineQueryResultArticle;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Log4j
class PersistenceService {
  private Connection dbConnection;
  private Statement findUserStatement;

  private String LENDER_STRING = "lender";
  private String LENDER_ID_STRING = "lender_id";
  private String PENDING_MONEY_STRING = "pending_money";
  // icon URL that shows up in inline results
  private String THUMBNAIL_URL_STRING = "http://via.placeholder.com/100x100";

  PersistenceService() {
    try {
      dbConnection = DriverManager.getConnection(BuildVars.DATABASE_LINK);
    } catch (SQLException e) {
      log.error(e.getMessage());
    }
  }

  /**
   * Returns list of LendUsers that have connections with current user.
   *
   * @param user current user
   * @return list of LendUsers that are wrapped in InlineQueryResult
   * @see User
   * @see LendUser
   * @see InlineQueryResult
   */
  List<InlineQueryResult> findInlineInfoWithUser(User user) {
    List<InlineQueryResult> resultArticleArrayList = new ArrayList<>();
    try {
      boolean isExecuted = getCurrentUserInformation(user, null);
      log.info("Statement execution status: " + isExecuted);
      if (isExecuted) {
        ResultSet resultSet = findUserStatement.getResultSet();
        int i = 0;
        while (resultSet.next()) {
          resultArticleArrayList.add(
              new InlineQueryResultArticle()
                  .setId(String.valueOf(++i))
                  .setTitle("Debt to " + resultSet.getString(LENDER_STRING))
                  .setDescription("How much money do I have to return to this person.")
                  .setInputMessageContent(
                      new InputTextMessageContent()
                          .setMessageText("I own " + resultSet.getString(LENDER_STRING) + " ₴" + resultSet.getString(PENDING_MONEY_STRING))
                          .disableWebPagePreview()
                  )
                  .setThumbUrl(THUMBNAIL_URL_STRING)
                  .setThumbHeight(100)
                  .setThumbWidth(100)
                  .setHideUrl(true));
        }
      }
    } catch (SQLException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    } finally {
      DbUtils.closeQuietly(findUserStatement);
    }
    return resultArticleArrayList;
  }

  /**
   * Method executes query to get information about current user.
   *
   * @param user     object to get information about
   * @param lenderId lender id to get only 1 result if present
   * @return result of query execution
   * @see User
   */
  private boolean getCurrentUserInformation(User user, Integer lenderId) throws SQLException {
    findUserStatement = dbConnection.createStatement();
    log.info("Created statement.");

    String findUserQuery = "SELECT " +
        "username, " +
        "CONCAT(first_name, ' ', last_name) as 'borrower', " +
        "sum AS '" + PENDING_MONEY_STRING + "', " +
        "(select CONCAT(first_name, ' ', last_name) from user where lender_id = user.telegram_id) as '" + LENDER_STRING + "', " +
        "lender_id as 'lender_id' " +
        "FROM lending " +
        " INNER JOIN user ON lending.borrower_id = user.telegram_id" +
        " WHERE borrower_id = " + user.getId();

    if (lenderId != null) {
      findUserQuery += " and lender_id = " + lenderId;
    }
    findUserQuery += ";";

    return findUserStatement.execute(findUserQuery);
  }

  /**
   * Adds user to lenders and specifies the sum that current user owns
   *
   * @param addContactCandidate user to be added
   * @param lendSum             own sum
   * @param adderId             current user id
   * @return result of add user query execution
   * @see Contact
   */
  boolean addLenderTo(Contact addContactCandidate, double lendSum, Integer adderId) {
    // Add contact candidate to user table query
    String addContactQuery = "INSERT ignore into user (telegram_id, username, first_name, last_name) VALUES (\n" +
        addContactCandidate.getUserID() + ", null, '" + addContactCandidate.getFirstName() + "', '" +
        addContactCandidate.getLastName() + "');";

    // Make connection between current user and lender query
    String addLendInfoQuery = "INSERT INTO lending (lender_id, borrower_id, lending.sum) VALUES (\n"
        + addContactCandidate.getUserID() + ", " + adderId + ", " + lendSum + ");";

    try {
      Statement addLenderStatement = dbConnection.createStatement();
      addLenderStatement.addBatch(addContactQuery);
      addLenderStatement.addBatch(addLendInfoQuery);
      int[] batchResults = addLenderStatement.executeBatch();
      log.info("Array: " + Arrays.toString(batchResults));
      return true;
    } catch (SQLException e) {
      log.error(e.getMessage());
      e.printStackTrace();
      return false;
    }
  }

  /**
   * Get list of all users that are connected with specified user
   *
   * @param user self explanatory
   * @return list of LendUser objects
   * @see User
   * @see LendUser
   */
  List<LendUser> getAllUsersInformation(User user) {
    List<LendUser> userData = new ArrayList<>();
    try {
      boolean isUserDataFound = getCurrentUserInformation(user, null);
      if (isUserDataFound) {
        ResultSet resultSet = findUserStatement.getResultSet();
        while (resultSet.next()) {
          userData.add(new LendUser(Integer.parseInt(resultSet.getString(LENDER_ID_STRING)), resultSet.getString(LENDER_STRING),
              Double.parseDouble(resultSet.getString(PENDING_MONEY_STRING))));
        }
      }
    } catch (SQLException e) {
      e.printStackTrace();
      log.error(e.getMessage());
    } finally {
      DbUtils.closeQuietly(findUserStatement);
    }
    return userData;
  }

  /**
   * Add or subtract sum from user. If user debt balance is zero or negative then delete user connection from the lending table.
   *
   * @param currentUserId current user id
   * @param editUserId    id of user to be edited
   * @param decreaseDebt  holds boolean to add or subtract sum
   * @param sum           amount of money
   * @return result of edit user query execution
   * @see Statement
   */
  boolean editUser(int currentUserId, int editUserId, boolean decreaseDebt, double sum) {
    String editUserQuery = "UPDATE lending set sum = sum " + (decreaseDebt ? "- " : "+ ") + sum + " where lender_id = " + editUserId + " and " +
        "borrower_id = " + currentUserId + ";";
    log.info("Edit query: " + editUserQuery);
    try {
      Statement editUserStatement = dbConnection.createStatement();
      editUserStatement.execute(editUserQuery);
      log.info(editUserStatement.getFetchSize());

      Statement updateNegativeSum = dbConnection.createStatement();
      updateNegativeSum.execute("DELETE FROM lending WHERE sum <= 0;");

      return true;
    } catch (SQLException e) {
      e.printStackTrace();
      log.error(e.getMessage());
      return false;
    }
  }

  /**
   * Returns number of user connections
   *
   * @param user self explanatory
   * @return number of connections
   * @see User
   */
  int getAmountOfUsers(User user) {
    return this.getAllUsersInformation(user).size();
  }

}
