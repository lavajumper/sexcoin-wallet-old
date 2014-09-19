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

package de.schildbach.wallet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Currency;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import android.util.Log;
import de.schildbach.wallet.exchange.GoogleRateLookup;
import de.schildbach.wallet.exchange.RateLookup;
import de.schildbach.wallet.exchange.YahooRateLookup;
import de.schildbach.wallet.exchange.CryptsyRateLookup;
import de.schildbach.wallet.util.GenericUtils;
import de.schildbach.wallet.util.Io;
import org.json.JSONObject;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.format.DateUtils;

/**
 * @author Andreas Schildbach, Litecoin Dev Team, Lavajumper
 */
public class ExchangeRatesProvider extends ContentProvider
{
    static final protected String TAG = ExchangeRatesProvider.class.getName();

	public static class ExchangeRate
	{
		public ExchangeRate(@Nonnull final String currencyCode, @Nonnull final BigInteger rate, @Nonnull final String source)
		{
			this.currencyCode = currencyCode;
			this.rate = rate;
			this.source = source;
		}

		public final String currencyCode;
		public final BigInteger rate;
		public final String source;

		@Override
		public String toString()
		{
			return getClass().getSimpleName() + '[' + currencyCode + ':' + GenericUtils.formatValue(rate, Constants.BTC_MAX_PRECISION, 0) + ']';
		}
	}

	public static final String KEY_CURRENCY_CODE = "currency_code";
	private static final String KEY_RATE = "rate";
	private static final String KEY_SOURCE = "source";

	@CheckForNull
	private Map<String, ExchangeRate> exchangeRates = null;
	private long lastUpdated = 0;

    private static final URL BTCE_URL;
    private static final String[] BTCE_FIELDS = new String[] { "avg" };
    private static final URL BTCE_EURO_URL;
    private static final String[] BTCE_EURO_FIELDS = new String[] { "avg" };
    private static final URL KRAKEN_URL;
    private static final URL KRAKEN_EURO_URL;
	private static final String[] KRAKEN_FIELDS = new String[] { "c" };
	private static final URL CRYPTSY_BTC_URL;
    private static final URL CRYPTSY_LTC_URL;
    private static final URL CRYPTSY_SXCBTC_URL;
    private static final URL CRYPTSY_SXCLTC_URL;
    private static final String[] CRYPTSY_BTC_FIELDS = new String[] { "BTC" };
    private static final String[] CRYPTSY_LTC_FIELDS = new String[] { "LTC" };
    private static final String[] CRYPTSY_SXCBTC_FIELDS = new String[] { "SXC" };
    private static final String[] CRYPTSY_SXCLTC_FIELDS = new String[] { "SXC" };
    
	static
	{
		try
		{
            BTCE_URL = new URL("https://btc-e.com/api/2/ltc_usd/ticker");
            BTCE_EURO_URL = new URL("https://btc-e.com/api/2/ltc_eur/ticker");
            KRAKEN_URL = new URL("https://api.kraken.com/0/public/Ticker?pair=XLTCZUSD");
            KRAKEN_EURO_URL = new URL("https://api.kraken.com/0/public/Ticker?pair=XLTCZEUR");
            CRYPTSY_BTC_URL = new URL("http://pubapi.cryptsy.com/api.php?method=singlemarketdata&marketid=2");
            CRYPTSY_LTC_URL = new URL("http://pubapi.cryptsy.com/api.php?method=singlemarketdata&marketid=1");
            CRYPTSY_SXCBTC_URL = new URL("http://pubapi.cryptsy.com/api.php?method=singlemarketdata&marketid=153");
            CRYPTSY_SXCLTC_URL = new URL("http://pubapi.cryptsy.com/api.php?method=singlemarketdata&marketid=98");
		}
		catch (final MalformedURLException x)
		{
			throw new RuntimeException(x); // cannot happen
		}
	}

	private static final long UPDATE_FREQ_MS = 10 * DateUtils.MINUTE_IN_MILLIS;

	// WTF...
	private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

	@Override
	public boolean onCreate()
	{
		return true;
	}

	public static Uri contentUri(@Nonnull final String packageName)
	{
		return Uri.parse("content://" + packageName + '.' + "exchange_rates");
	}

	@Override
	public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs, final String sortOrder)
	{
		final long now = System.currentTimeMillis();

		if (exchangeRates == null || now - lastUpdated > UPDATE_FREQ_MS)
		{

			Map<String, ExchangeRate> newExchangeRates = null;
            // Attempt to get USD exchange rates from all providers.  Stop after first.
			newExchangeRates = requestCryptsyExchangeRates(CRYPTSY_LTC_URL, "USD", CRYPTSY_LTC_FIELDS);
			//newExchangeRates = requestExchangeRates(BTCE_URL, "USD", BTCE_FIELDS);
			if (newExchangeRates == null)
            {
                Log.i(TAG, "Failed to fetch BTC USD rates");
				newExchangeRates = requestExchangeRates(KRAKEN_URL, "USD", KRAKEN_FIELDS);
            }

            if (newExchangeRates == null)
            {
                Log.i(TAG, "Failed to fetch KRAKEN USD rates");
                // Continue without USD rate (shouldn't generally happen)
            }

            // Get Euro rates as a fallback if Yahoo! fails below
            Map<String, ExchangeRate> euroRate = requestExchangeRates(BTCE_EURO_URL, "EUR", BTCE_EURO_FIELDS);
            if (euroRate == null)
            {
                Log.i(TAG, "Failed to fetch BTCE EUR rates");
                euroRate = requestExchangeRates(KRAKEN_EURO_URL, "EUR", KRAKEN_FIELDS);
            }

            if (euroRate == null)
            {
                Log.i(TAG, "Failed to fetch KRAKEN EUR rates");
            }
            else
            {
                if(newExchangeRates != null)
                    newExchangeRates.putAll(euroRate);
                else
                    newExchangeRates = euroRate;
            }

            if (newExchangeRates != null)
			{
                // Get USD conversion exchange rates from Google/Yahoo
                ExchangeRate usdRate = newExchangeRates.get("USD");
                //ExchangeRate btcRate = newExchangeRates.get("BTC");
                //RateLookup providers[] = {new GoogleRateLookup(), new YahooRateLookup()};
                RateLookup providers[] = { new CryptsyRateLookup() };
                Map<String, ExchangeRate> fiatRates;
                for(RateLookup provider : providers) {
                    fiatRates = provider.getRates(usdRate);
                    if(fiatRates != null) {
                        // Remove EUR if we have a better source above
                        if(euroRate != null)
                            fiatRates.remove("EUR");
                        // Remove USD rate because we already have it
                        fiatRates.remove("USD");
                        newExchangeRates.putAll(fiatRates);
                        break;
                    }
                }

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
				cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
			}
		}
		else if (selection.equals(KEY_CURRENCY_CODE))
		{
			final String selectedCode = selectionArgs[0];
			ExchangeRate rate = selectedCode != null ? exchangeRates.get(selectedCode) : null;

			if (rate == null)
			{
				final String defaultCode = defaultCurrencyCode();
				rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

				if (rate == null)
				{
					rate = exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);

					if (rate == null)
						return null;
				}
			}

			cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
		}

		return cursor;
	}

	private String defaultCurrencyCode()
	{
		try
		{
			return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
		}
		catch (final IllegalArgumentException x)
		{
			return null;
		}
	}

	public static ExchangeRate getExchangeRate(@Nonnull final Cursor cursor)
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

	private static Map<String, ExchangeRate> requestExchangeRates(final URL url, final String currencyCode, final String... fields)
	{
		HttpURLConnection connection = null;
		Reader reader = null;

		try
		{
			connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.connect();

			final int responseCode = connection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK)
			{
				reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 4096), Constants.UTF_8);
				final StringBuilder content = new StringBuilder();
				Io.copy(reader, content);
				//log.info("RETURN FROM JSON: " + content.toString());
				final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

				final JSONObject head = new JSONObject(content.toString());
				//log.info("JSON 0: " + head.toString(4));
				
				for (final Iterator<String> i = head.keys(); i.hasNext();)
				{
					String code = i.next();

					if (!"timestamp".equals(code))
					{
						final JSONObject o = head.getJSONObject(code);
						for (final String field : fields)
						{
							final String rateStr = o.optString(field, null);
							if (rateStr != null)
							{
								try
								{
									final BigInteger rate = GenericUtils.toNanoCoinsRounded(rateStr, 0);

									if (rate.signum() > 0)
									{
										rates.put(currencyCode, new ExchangeRate(currencyCode, rate, url.getHost()));
										break;
									}
								}
								catch (final ArithmeticException x)
								{
									log.warn("problem fetching exchange rate: " + currencyCode, x);
								}
							}
						}
					}
				}

				log.info("fetched exchange rates from " + url);

				return rates;
			}
			else
			{
				log.warn("http status " + responseCode + " when fetching " + url);
			}
		}
		catch (final Exception x)
		{
			log.warn("problem fetching exchange rates", x);
		}
		finally
		{
			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (final IOException x)
				{
					// swallow
				}
			}

			if (connection != null)
				connection.disconnect();
		}

		return null;
	}

	
	private static Map<String, ExchangeRate> requestCryptsyExchangeRates(final URL url, final String currencyCode, final String... fields)
	{
		HttpURLConnection connection = null;
		Reader reader = null;
		log.info("Currency code = " + currencyCode);
		log.info("fields " + Arrays.toString(fields));
		log.info("RETRIEVING " + url.toString());
		BigInteger sxcrate;
		try
		{
			connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.connect();
	
			final int responseCode = connection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK)
			{
				reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 128), Constants.UTF_8);
				final StringBuilder content = new StringBuilder();
				Io.copy(reader, content);
				String saveContent = content.toString();
				String newContent = saveContent.replace(":1," , ":\"1\",");

				//logBigString("RETURN FROM JSON: " + newContent);
				final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
				try{
					final JSONObject o = new JSONObject(newContent);
					JSONObject data = o.getJSONObject("return").getJSONObject("markets").getJSONObject(fields[0]);
					String price = data.getString("lasttradeprice");
					BigInteger rate = GenericUtils.toNanoCoinsRounded(price, 0);
					if (rate.signum() > 0)
					{
						if(fields[0].equals("BTC")){
							sxcrate = getSexcoinRate(CRYPTSY_SXCBTC_URL);
						}else{
							sxcrate = getSexcoinRate(CRYPTSY_SXCLTC_URL);
						}
						rate = sxcrate.divide(rate);
						rates.put(currencyCode, new ExchangeRate(currencyCode, rate, url.getHost()));
					}
					 
				}catch(Exception e){
					logBigString("***O.exception          : " + e.getMessage());
				}
				return rates;
			}
			else
			{
				log.warn("http status " + responseCode + " when fetching " + url);
			}
		}
		catch (final Exception x)
		{
			log.warn("problem fetching exchange rates", x);
		}
		finally
		{
			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (final IOException x)
				{
					// swallow
				}
			}
	
			if (connection != null)
				connection.disconnect();
		}
	
		return null;
	}
	
	private static BigInteger getSexcoinRate ( URL url ){
		HttpURLConnection connection = null;
		Reader reader = null;
		log.info("RETRIEVING " + url.toString());
		try
		{
			connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.connect();
	
			final int responseCode = connection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK)
			{
				reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 128), Constants.UTF_8);
				final StringBuilder content = new StringBuilder();
				Io.copy(reader, content);
				String saveContent = content.toString();
				String newContent = saveContent.replace(":1," , ":\"1\",");

				//logBigString("RETURN FROM JSON: " + newContent);
				try{
					final JSONObject o = new JSONObject(newContent);
					JSONObject data = o.getJSONObject("return").getJSONObject("markets").getJSONObject("SXC");
					String price = data.getString("lasttradeprice");
					final BigInteger rate = GenericUtils.toNanoCoinsRounded(price, 0);
					return rate;
				}catch(Exception e){
					logBigString("***SXC.exception          : " + e.getMessage());
				}
				
			}
			else
			{
				log.warn("http status " + responseCode + " when fetching " + url);
			}
		}
		catch (final Exception x)
		{
			log.warn("problem fetching sxc exchange rates", x);
		}
		finally
		{
			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (final IOException x)
				{
					// swallow
				}
			}
	
			if (connection != null)
				connection.disconnect();
		}
	
		return null;		
	}
	
	private static void logBigString(String msg, int lineLength){
		int len = msg.length();
		if(len > lineLength){ lineLength = len; }
		int numStrings=(int)(len/lineLength) + 1;
		int end = len;
		
		for(int i=0; i< numStrings ; i++){
			end = lineLength*i + lineLength;
			if( end > len ) { end = len; }
			log.info("--" + msg.substring(lineLength*i,end));
		}
		log.info("<<");		
	}
	private static void logBigString(String msg){
		logBigString(msg,80);

	}
}
