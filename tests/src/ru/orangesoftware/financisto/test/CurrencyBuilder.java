package ru.orangesoftware.financisto.test;

import ru.orangesoftware.financisto.db.DatabaseAdapter;
import ru.orangesoftware.financisto.model.Currency;

import static junit.framework.Assert.assertNotNull;

/**
 * Created by IntelliJ IDEA.
 * User: Denis Solonenko
 * Date: 3/2/11 9:11 PM
 */
public class CurrencyBuilder {

    private final DatabaseAdapter db;
    private final Currency c = new Currency();

    public static Currency createDefault(DatabaseAdapter db) {
        return withDb(db).title("Singapore Dollar").name("SGD").symbol("S$").create();
    }

    public static CurrencyBuilder withDb(DatabaseAdapter db) {
        return new CurrencyBuilder(db);
    }

    private CurrencyBuilder(DatabaseAdapter db) {
        this.db = db;
    }

    public CurrencyBuilder title(String title) {
        c.title = title;
        return this;
    }

    public CurrencyBuilder name(String name) {
        c.name = name;
        return this;
    }

    public CurrencyBuilder symbol(String symbol) {
        c.symbol = symbol;
        return this;
    }

    public Currency create() {
        db.em().saveOrUpdate(c);
        return c;
    }

}
