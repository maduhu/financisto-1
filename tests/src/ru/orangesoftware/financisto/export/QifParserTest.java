/*
 * Copyright (c) 2011 Denis Solonenko.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 */

package ru.orangesoftware.financisto.export;

import android.test.AndroidTestCase;
import ru.orangesoftware.financisto.export.qif.*;
import ru.orangesoftware.financisto.model.Account;
import ru.orangesoftware.financisto.model.Category;
import ru.orangesoftware.financisto.test.CategoryBuilder;
import ru.orangesoftware.financisto.test.DateTime;
import ru.orangesoftware.financisto.test.TransactionBuilder;
import ru.orangesoftware.financisto.test.TransferBuilder;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

import static ru.orangesoftware.financisto.test.DateTime.date;

/**
 * Created by IntelliJ IDEA.
 * User: Denis Solonenko
 * Date: 9/25/11 9:53 PM
 */
public class QifParserTest extends AndroidTestCase {

    QifParser p;

    public void test_should_parse_empty_file() throws IOException {
        parseQif("");
    }

    public void test_should_parse_empty_account() throws IOException {
        parseQif(
                "!Account\n" +
                "NMy Cash Account\n" +
                "TCash\n" +
                "^\n");
        assertEquals(1, p.accounts.size());
        assertEquals("My Cash Account", p.accounts.get(0).memo);
        assertEquals("Cash", p.accounts.get(0).type);
    }

    public void test_should_parse_a_couple_of_empty_accounts() throws IOException {
        parseQif(
                "!Account\n" +
                "NMy Cash Account\n" +
                "TCash\n" +
                "^\n" +
                "!Account\n" +
                "NMy Bank Account\n" +
                "TBank\n" +
                "^\n");
        assertEquals(2, p.accounts.size());
        assertEquals("My Cash Account", p.accounts.get(0).memo);
        assertEquals("Cash", p.accounts.get(0).type);
        assertEquals("My Bank Account", p.accounts.get(1).memo);
        assertEquals("Bank", p.accounts.get(1).type);
    }

    public void test_should_parse_account_with_a_couple_of_transactions() throws Exception {
        parseQif(
                "!Type:Cat\n" +
                "NP1\n" +
                "E\n" +
                "^\n" +
                "NP1:c1\n" +
                "E\n" +
                "^\n" +
                "NP2\n" +
                "I\n" +
                "^\n" +
                "!Account\n" +
                "NMy Cash Account\n" +
                "TCash\n" +
                "^\n" +
                "!Type:Cash\n" +
                "D08/02/2011\n" +
                "T10.00\n" +
                "LP1\n" +
                "^\n" +
                "D07/02/2011\n" +
                "T-20.56\n" +
                "LP1:c1\n" +
                "PPayee 1\n" +
                "MSome note here...\n" +
                "^\n");

        assertEquals(3, p.categories.size());

        QifCategory c = p.categories.get(0);
        assertEquals("P1", c.name);
        assertEquals(false, c.isIncome);
        c = p.categories.get(1);
        assertEquals("P1:c1", c.name);
        assertEquals(false, c.isIncome);
        c = p.categories.get(2);
        assertEquals("P2", c.name);
        assertEquals(true, c.isIncome);

        assertEquals(1, p.accounts.size());

        QifAccount a = p.accounts.get(0);
        assertEquals("My Cash Account", a.memo);
        assertEquals("Cash", a.type);

        assertEquals(2, a.transactions.size());
        QifTransaction t = a.transactions.get(0);
        assertEquals(DateTime.date(2011, 2, 8).atMidnight().asDate(), t.date);
        assertEquals(1000, t.amount);
        assertEquals("P1", t.category);

        t = a.transactions.get(1);
        assertEquals(DateTime.date(2011, 2, 7).atMidnight().asDate(), t.date);
        assertEquals(-2056, t.amount);
        assertEquals("P1:c1", t.category);
        assertEquals("Payee 1", t.payee);
        assertEquals("Some note here...", t.memo);
    }

    public void test_should_parse_multiple_accounts() throws Exception {
        parseQif(
                "!Account\n" +
                "NMy Cash Account\n" +
                "TCash\n" +
                "^\n" +
                "!Type:Cash\n" +
                "D08/02/2011\n" +
                "T10.00\n" +
                "^\n" +
                "D07/02/2011\n" +
                "T-23.45\n" +
                "^\n" +
                "D01/01/2011\n" +
                "T-67.80\n" +
                "^\n" +
                "!Account\n" +
                "NMy Bank Account\n" +
                "TBank\n" +
                "^\n" +
                "!Type:Bank\n" +
                "D08/02/2011\n" +
                "T-20.00\n" +
                "^\n" +
                "D02/01/2011\n" +
                "T54.00\n" +
                "^\n");

        assertEquals(2, p.accounts.size());

        QifAccount a = p.accounts.get(0);
        assertEquals("My Cash Account", a.memo);
        assertEquals("Cash", a.type);

        assertEquals(3, a.transactions.size());

        QifTransaction t = a.transactions.get(0);
        assertEquals(DateTime.date(2011, 2, 8).atMidnight().asDate(), t.date);
        assertEquals(1000, t.amount);

        t = a.transactions.get(1);
        assertEquals(DateTime.date(2011, 2, 7).atMidnight().asDate(), t.date);
        assertEquals(-2345, t.amount);

        t = a.transactions.get(2);
        assertEquals(DateTime.date(2011, 1, 1).atMidnight().asDate(), t.date);
        assertEquals(-6780, t.amount);

        a = p.accounts.get(1);
        assertEquals("My Bank Account", a.memo);
        assertEquals("Bank", a.type);

        t = a.transactions.get(0);
        assertEquals(DateTime.date(2011, 2, 8).atMidnight().asDate(), t.date);
        assertEquals(-2000, t.amount);

        t = a.transactions.get(1);
        assertEquals(DateTime.date(2011, 1, 2).atMidnight().asDate(), t.date);
        assertEquals(5400, t.amount);
    }

    public void test_should_parse_transfers() throws Exception {
        parseQif(
                "!Account\n" +
                        "NMy Cash Account\n" +
                        "TCash\n" +
                        "^\n" +
                        "!Type:Cash\n" +
                        "D08/02/2011\n" +
                        "T20.00\n" +
                        "L[My Bank Account]\n" +
                        "^\n" +
                        "!Account\n" +
                        "NMy Bank Account\n" +
                        "TBank\n" +
                        "^\n" +
                        "!Type:Bank\n" +
                        "D08/02/2011\n" +
                        "T-20.00\n" +
                        "L[My Cash Account]\n" +
                        "^\n");

        assertEquals(2, p.accounts.size());

        QifAccount a = p.accounts.get(0);
        assertEquals("My Cash Account", a.memo);
        assertEquals("Cash", a.type);

        assertEquals(1, a.transactions.size());

        QifTransaction t = a.transactions.get(0);
        assertEquals(DateTime.date(2011, 2, 8).atMidnight().asDate(), t.date);
        assertEquals("[My Bank Account]", t.category);
        assertEquals(2000, t.amount);

        a = p.accounts.get(1);
        assertEquals("My Bank Account", a.memo);
        assertEquals("Bank", a.type);

        assertEquals(1, a.transactions.size());

        t = a.transactions.get(0);
        assertEquals(DateTime.date(2011, 2, 8).atMidnight().asDate(), t.date);
        assertEquals("[My Cash Account]", t.category);
        assertEquals(-2000, t.amount);
    }

    public void test_should_parse_splits() throws Exception {
        parseQif(
            "!Type:Cat\nNA\nE\n^\nNA:A1\nE\n^\nNA:A1:AA1\nE\n^\nNA:A2\nE\n^\nNB\nE\n^\n"+ // this is not important
            "!Account\n"+
            "NMy Cash Account\n"+
            "TCash\n"+
            "^\n"+
            "!Type:Cash\n"+
            "D12/07/2011\n"+
            "T-2,600.66\n"+
            "SA:A1\n"+
            "$-1,100.56\n"+
            "ENote on first split\n"+
            "SA:A2\n"+
            "$-1,000.00\n"+
            "S<NO_CATEGORY>\n"+
            "$500.10\n"+
            "ENote on third split\n"+
            "^\n");
        assertEquals(1, p.accounts.size());

        QifAccount a = p.accounts.get(0);
        assertEquals(1, a.transactions.size());

        QifTransaction t = a.transactions.get(0);
        assertEquals(3, t.splits.size());

        QifTransaction s = t.splits.get(0);
        assertEquals("A:A1", s.category);
        assertEquals(-110056, s.amount);

        s = t.splits.get(1);
        assertEquals("A:A2", s.category);
        assertEquals(-100000, s.amount);

        s = t.splits.get(2);
        assertEquals("<NO_CATEGORY>", s.category);
        assertEquals(50010, s.amount);
        assertEquals("Note on third split", s.memo);
    }

    private void parseQif(String fileContent) throws IOException {
        QifBufferedReader r = new QifBufferedReader(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(fileContent.getBytes()), "UTF-8")));
        p = new QifParser(r);
        p.parse();
    }


}