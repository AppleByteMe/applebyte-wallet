/*
 * Copyright 2011-2013 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.applebyte;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.format.DateUtils;

import com.google.applebyte.core.Utils;

import de.schildbach.wallet.applebyte.util.IOUtils;

/**
 * @author Andreas Schildbach
 */
public class ExchangeRatesProvider extends ContentProvider
{
	public static class ExchangeRate
	{
		public ExchangeRate(final String currencyCode, final BigInteger rate, final String source)
		{
			this.currencyCode = currencyCode;
			this.rate = rate;
			this.source = source;
		}

		public final String currencyCode;
		public final BigInteger rate;
		public final String source;
	}

	public static final String KEY_CURRENCY_CODE = "currency_code";
	private static final String KEY_RATE = "rate";
	private static final String KEY_SOURCE = "source";

	private Map<String, ExchangeRate> exchangeRates = null;
	private long lastUpdated = 0;

	private static final long UPDATE_FREQ_MS = DateUtils.HOUR_IN_MILLIS;
	private static final int TIMEOUT_MS = 15 * (int) DateUtils.SECOND_IN_MILLIS;

	@Override
	public boolean onCreate()
	{
		return true;
	}

	public static Uri contentUri(final String packageName)
	{
		return Uri.parse("content://" + packageName + '.' + "exchange_rates");
	}

	@Override
	public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs, final String sortOrder)
	{
		final long now = System.currentTimeMillis();

		if (exchangeRates == null || now - lastUpdated > UPDATE_FREQ_MS)
		{
			Map<String, ExchangeRate> newExchangeRates = getApplebyteCharts();
			if (exchangeRates == null && newExchangeRates == null)
				newExchangeRates = getBlockchainInfo();

			if (newExchangeRates != null)
			{
				exchangeRates = newExchangeRates;
				lastUpdated = now;
			}
		}

		if (exchangeRates == null)
			return null;

		final MatrixCursor cursor = new MatrixCursor(new String[] { BaseColumns._ID, KEY_CURRENCY_CODE, KEY_RATE, KEY_SOURCE });

		if (selection == null)
		{
			for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet())
			{
				final ExchangeRate rate = entry.getValue();
				cursor.newRow().add(entry.getKey().hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
			}
		}
		else if (selection.equals(KEY_CURRENCY_CODE))
		{
			final String code = selectionArgs[0];
			final ExchangeRate rate = exchangeRates.get(code);
            try {
			  cursor.newRow().add(code.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
            } catch (NullPointerException e) {
                Log.e("ArtByte", "Unable to add an exchange rate.  NullPointerException.");
            }
		}

		return cursor;
	}

	public static ExchangeRate getExchangeRate(final Cursor cursor)
	{
		final String currencyCode = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_CODE));
		final BigInteger rate = BigInteger.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE)));
		final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

		return new ExchangeRate(currencyCode, rate, source);
	}

	@Override
	public Uri insert(final Uri uri, final ContentValues values)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(final Uri uri, final String selection, final String[] selectionArgs)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public String getType(final Uri uri)
	{
		throw new UnsupportedOperationException();
	}

	private static Map<String, ExchangeRate> getApplebyteCharts()
	{
        final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
        // Keep the BTC rate around for a bit
        Double btcRate = 0.0;
		try {
            // Handle ABY/USD special since we have to do maths
            URL URL = null;
            try {
                URL = new URL("https://poloniex.com/public?command=returnTicker");
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
            URLConnection connection = URL.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.connect();
            StringBuilder content = new StringBuilder();

            Reader reader = null;
            try
            {
                reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
                IOUtils.copy(reader, content);
                final JSONObject head = new JSONObject(content.toString());
                JSONObject ticker = head.getJSONObject("BTC_ABY");
                Double avg = ticker.getDouble("last");
                // This is applebytes priced in bitcoins
                btcRate = avg;
                String s_avg = String.format("%.4f", avg).replace(',', '.');
                // rates.put("BTC", new ExchangeRate("BTC", Utils.toNanoCoins(s_avg), URL.getHost()));
            } finally
            {
                if (reader != null)
                    reader.close();
            }
            // Handle ABY/USD special since we have to do maths
            URL = null;
            URL = new URL("https://btc-e.com/api/2/btc_usd/ticker");
            connection = URL.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.connect();
            content = new StringBuilder();

            reader = null;
            try
            {
                reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
                IOUtils.copy(reader, content);
                final JSONObject head = new JSONObject(content.toString());
                JSONObject ticker = head.getJSONObject("ticker");
                Double avg = ticker.getDouble("avg");
                // This is bitcoins priced in dollars.  We want ABY!
                avg *= btcRate;
                String s_avg = String.format("%.4f", avg).replace(',', '.');
                // rates.put("USD", new ExchangeRate("USD", Utils.toNanoCoins(s_avg), URL.getHost()));
            } finally
            {
                if (reader != null)
                    reader.close();
            }
            return rates;
        }
		catch (final IOException x)
		{
			x.printStackTrace();
		}
		catch (final JSONException x)
		{
			x.printStackTrace();
		}

		return null;
	}

	private static Map<String, ExchangeRate> getBlockchainInfo()
	{
		try
		{
			final URL URL = new URL("https://blockchain.info/ticker");
			final URLConnection connection = URL.openConnection();
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.connect();
			final StringBuilder content = new StringBuilder();

			Reader reader = null;
			try
			{
				reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
				IOUtils.copy(reader, content);

				final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

				final JSONObject head = new JSONObject(content.toString());
				for (final Iterator<String> i = head.keys(); i.hasNext();)
				{
					final String currencyCode = i.next();
					final JSONObject o = head.getJSONObject(currencyCode);
					final String rate = o.optString("15m", null);

					if (rate != null)
						rates.put(currencyCode, new ExchangeRate(currencyCode, Utils.toNanoCoins(rate), URL.getHost()));
				}

				return rates;
			}
			finally
			{
				if (reader != null)
					reader.close();
			}
		}
		catch (final IOException x)
		{
			x.printStackTrace();
		}
		catch (final JSONException x)
		{
			x.printStackTrace();
		}

		return null;
	}

	// https://bitmarket.eu/api/ticker
}
