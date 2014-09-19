package de.schildbach.wallet.exchange;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;
import de.schildbach.wallet.ExchangeRatesProvider;
import de.schildbach.wallet.util.GenericUtils;

public class CryptsyRateLookup extends RateLookup {

    private static final String TAG = CryptsyRateLookup.class.getName();

    public CryptsyRateLookup()
    {
        super("http://pubapi.cryptsy.com/api.php?method=singlemarketdata&marketid=153");
    }

    public Map<String, ExchangeRatesProvider.ExchangeRate> getRates(ExchangeRatesProvider.ExchangeRate usdRate) {
        if(usdRate == null)
            return null;
        final BigDecimal decUsdRate = GenericUtils.fromNanoCoins(usdRate.rate, 0);
        if(getData())
        {
            // We got data from the HTTP connection
            final Map<String, ExchangeRatesProvider.ExchangeRate> rates =
                    new TreeMap<String, ExchangeRatesProvider.ExchangeRate>();
            try
            {
                JSONObject head = new JSONObject(this.data);
                head = head.getJSONObject("result").getJSONObject("markets").getJSONObject("SXC");

                JSONArray resultArray = head.getJSONArray("SXC");
                Log.i(TAG,resultArray.toString(2));
                // Format: eg. _cpzh4: 3.673
                Pattern p = Pattern.compile("_cpzh4: ([\\d\\.]+)");
                for(int i = 0; i < resultArray.length(); ++i) {
                    String currencyCd = resultArray.getJSONObject(i).getJSONObject("title").getString("$t");
                    String rateStr = resultArray.getJSONObject(i).getJSONObject("content").getString("$t");
                    Matcher m = p.matcher(rateStr);
                    if(m.matches())
                    {
                        // Just get the good part
                        rateStr = m.group(1);
                        Log.d(TAG, "Currency: " + currencyCd);
                        Log.d(TAG, "Rate: " + rateStr);
                        Log.d(TAG, "BTC Rate: " + decUsdRate.toString());
                        BigDecimal rate = new BigDecimal(rateStr);
                        Log.d(TAG, "Converted Rate: " + rate.toString());
                        rate = decUsdRate.multiply(rate);
                        Log.d(TAG, "Final Rate: " + rate.toString());
                        if (rate.signum() > 0)
                        {
                            rates.put(currencyCd, new ExchangeRatesProvider.ExchangeRate(currencyCd,
                                    GenericUtils.toNanoCoinsRounded(rate.toString(), 0), this.url.getHost()));
                        }
                    }else{
                    	Log.d(TAG,"rateStr = " + rateStr);
                    }
                    
                }
            } catch(JSONException e) {
                Log.i(TAG, "Bad JSON response from Cryptsy API!: " + data);
                return null;
            }
            return rates;
        }
        return null;
    }
	
}
