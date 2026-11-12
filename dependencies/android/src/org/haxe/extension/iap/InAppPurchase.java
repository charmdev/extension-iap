package org.haxe.extension.iap;

import android.opengl.GLSurfaceView;
import android.os.Handler;
import java.util.ArrayList;

import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.Purchase.PurchaseState;
import com.android.billingclient.api.ProductDetails;
import org.json.JSONObject;
import com.android.billingclient.api.QueryProductDetailsParams;

import org.haxe.extension.Extension;
import org.haxe.extension.iap.util.BillingManager;
import org.haxe.extension.iap.util.BillingManager.BillingUpdatesListener;
import org.haxe.extension.iap.util.Log;
import org.haxe.lime.HaxeObject;
import org.json.JSONException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class InAppPurchase extends Extension {
	
	private static String TAG = "BillingManager";
	private static HaxeObject callback = null;
	private static BillingManager billingManager = null;
	private static String publicKey = "";
	private static UpdateListener updateListener = null;
	private static Map<String, Purchase> consumeInProgress = new HashMap<String, Purchase>();
	private static Map<String, Purchase> acknowledgePurchaseInProgress = new HashMap<String, Purchase>();
	private static Boolean complete = false;
	
	private static class UpdateListener implements BillingUpdatesListener {
		@Override
		public void onBillingClientSetupFinished(final Boolean success) {
			InAppPurchase.complete = false;
			
			if (success) {
				fireCallback("onStarted", new Object[] { "Success" });
				Log.d("BILLING onStarted Success");
			}
			else {
				fireCallback("onStarted", new Object[] { "Failure" });
				Log.d("BILLING onStarted Failure");
			}
		}

		@Override
		public void onConsumeFinished(String token, final BillingResult result) {
			Log.d("BILLING Consumption finished. Purchase token: " + token + ", result: " + result);
			final Purchase purchase = InAppPurchase.consumeInProgress.get(token);
			InAppPurchase.consumeInProgress.remove(token);
			if (result.getResponseCode() == BillingResponseCode.OK) {
				fireCallback("onConsume", new Object[] { purchase.getOriginalJson() });
			} else {
				fireCallback("onFailedConsume", new Object[] { ("{\"result\":" + result + ", \"product\":" + purchase.getOriginalJson() + "}") });
			}
		}

		@Override
		public void onAcknowledgePurchaseFinished(String token, final BillingResult result) {
			Log.d("BILLING Consumption finished. Purchase token: " + token + ", result: " + result);
			final Purchase purchase = InAppPurchase.acknowledgePurchaseInProgress.get(token);
			InAppPurchase.acknowledgePurchaseInProgress.remove(token);
			if (result.getResponseCode() == BillingResponseCode.OK) {
				fireCallback("onAcknowledgePurchase", new Object[] { purchase.getOriginalJson() });
			} else {
				fireCallback("onFailedAcknowledgePurchase", new Object[] { ("{\"result\":" + result + ", \"product\":" + purchase.getOriginalJson() + "}") });
			}
		}

		@Override
		public void onPurchasesUpdated(List<Purchase> purchaseList, final BillingResult result) {
			Log.d("BILLING onPurchasesUpdated: " + result);
			if (result.getResponseCode() == BillingResponseCode.OK)
			{
				for (Purchase purchase : purchaseList) 
				{
					if (purchase.getPurchaseState() == PurchaseState.PURCHASED) {
						fireCallback("onPurchase", new Object[]{purchase.getOriginalJson(), "", purchase.getSignature()});
					}
					else if (purchase.getPurchaseState() == PurchaseState.PENDING) {
						fireCallback("onPending", new Object[]{purchase.getOriginalJson(), "", purchase.getSignature()});
					}
				}
			}
			else
			{
				if (result.getResponseCode() == BillingResponseCode.USER_CANCELED)
				{
					fireCallback("onCanceledPurchase", new Object[] { "canceled" });
				}
				else
				{
					String message = "{\"result\":{\"message\":\"" + result + "\"}}";
					Log.d("BILLING onFailedPurchase: " + message);
					fireCallback("onFailedPurchase", new Object[] { (message) });
				}
			}
		}

		public static void largeLog(String tag, String content) {
			if (content.length() > 4000) {
				Log.d(tag + "::: " + content.substring(0, 4000));
				largeLog(tag, content.substring(4000));
			} else {
				Log.d(tag + "::: " + content);
			}
		}

		@Override
		public void onQueryProductDetailsFinished(List<ProductDetails> purchaseList, final BillingResult result) {
			Log.d("BILLING onQueryProductDetailsFinished: result: " + result.getResponseCode());

			if (result.getResponseCode() == BillingResponseCode.OK) {
			
				String jsonResp =  "{ \"products\":[ ";

				for (ProductDetails sku : purchaseList) {
					String productJson = InAppPurchase.productJson(sku);
					if (productJson != null) {
						jsonResp += productJson + ",";
					}
				}

				jsonResp = jsonResp.substring(0, jsonResp.length() - 1);
				jsonResp += "]}";
				Log.d("BILLING onQueryProductDetailsFinished: " + jsonResp);
				
				//largeLog("BILLING largeLog", jsonResp);

				InAppPurchase.complete = true;
				fireCallback("onRequestProductDataComplete", new Object[] { jsonResp });
			}
			else {
				Log.d("BILLING onQueryProductDetailsFinished Failure: ");

				InAppPurchase.complete = true;
				fireCallback("onRequestProductDataComplete", new Object[] { "Failure" });
			}
		}

		@Override
		public void onQueryPurchasesFinished(List<Purchase> purchaseList) {
			String jsonResp =  "{ \"purchases\":[ ";
			for (Purchase purchase : purchaseList) {
				for (String sku : purchase.getProducts()) {
					jsonResp += "{" +
							"\"key\":\"" + sku +"\", " +
							"\"value\":" + purchase.getOriginalJson() + "," +
							"\"purchaseState\":\"" + purchase.getPurchaseState() +"\", " +
							"\"itemType\":\"\"," +
							"\"signature\":\"" + purchase.getSignature() + "\"},";
				}
			}
			jsonResp = jsonResp.substring(0, jsonResp.length() - 1);
			jsonResp += "]}";
			fireCallback("onQueryInventoryComplete", new Object[] { jsonResp });

			Log.d("BILLING onQueryInventoryComplete " + jsonResp);
		}
		
		@Override
		public void onQueryPurchasesFailed() {
			fireCallback("onQueryInventoryFailed", new Object[] { "" });
		}
	}

	public static void buy (final String productID, final String devPayload) {
		// IabHelper.launchPurchaseFlow() must be called from the main activity's UI thread
		Extension.mainActivity.runOnUiThread(new Runnable() 
		{
			@Override
			public void run() 
			{
				InAppPurchase.billingManager.initiatePurchaseFlow(productID);
			}
		});
	}
	
	public static void consume (final String purchaseJson, final String signature) 
	{
		Extension.mainActivity.runOnUiThread(new Runnable() 
		{
			public void run()
			{
				try
				{
					final Purchase purchase = new Purchase(purchaseJson, signature);
					InAppPurchase.consumeInProgress.put(purchase.getPurchaseToken(), purchase);
					InAppPurchase.billingManager.consumeAsync(purchase.getPurchaseToken());
				}
				catch(JSONException e)
				{
					fireCallback("onFailedConsume", new Object[] { "" });
				}
			}
		});
	}
	
	public static void acknowledgePurchase (final String purchaseJson, final String signature)
	{
		Extension.mainActivity.runOnUiThread(new Runnable() 
		{
			public void run()
			{
				try
				{
					final Purchase purchase = new Purchase(purchaseJson, signature);
					if (!purchase.isAcknowledged()) {
						InAppPurchase.acknowledgePurchaseInProgress.put(purchase.getPurchaseToken(), purchase);
						InAppPurchase.billingManager.acknowledgePurchase(purchase.getPurchaseToken());
					}
				}
				catch(JSONException e)
				{
					fireCallback("onFailedAcknowledgePurchase", new Object[] { "" });
				}
			}
		});
	}

	private static void fireCallback(final String name, final Object[] payload)
	{
		if (Extension.mainView == null || InAppPurchase.callback == null) return;

		if (Extension.mainView instanceof GLSurfaceView)
		{
			GLSurfaceView view = (GLSurfaceView) Extension.mainView;
			view.queueEvent(new Runnable()
			{
				public void run()
				{
					InAppPurchase.callback.call(name, payload);
				}
			});
		}
		else
		{
			Extension.mainActivity.runOnUiThread(new Runnable()
			{
				@Override
				public void run()
				{
					InAppPurchase.callback.call(name, payload);
				}
			});
		}
	}

	private static final String PRODUCT_JSON_START = "jsonString='";
	private static final String PRODUCT_JSON_END = "', parsedJson=";

	// ProductDetails has no public getter for the original JSON, so it is taken from toString()
	private static String productJson(ProductDetails sku) {
		String resSku = sku.toString();
		int start = resSku.indexOf(PRODUCT_JSON_START);
		int end = resSku.indexOf(PRODUCT_JSON_END, start);

		try {
			JSONObject json = (start >= 0 && end > start)
				? new JSONObject(resSku.substring(start + PRODUCT_JSON_START.length(), end))
				: new JSONObject().put("productId", sku.getProductId());

			// Play now sends an empty oneTimePurchaseOfferDetails and the price in oneTimePurchaseOfferDetailsList
			ProductDetails.OneTimePurchaseOfferDetails offer = sku.getOneTimePurchaseOfferDetails();
			List<ProductDetails.OneTimePurchaseOfferDetails> offers = sku.getOneTimePurchaseOfferDetailsList();
			if (offer == null && offers != null && !offers.isEmpty()) {
				offer = offers.get(0);
			}

			JSONObject currentOffer = json.optJSONObject("oneTimePurchaseOfferDetails");
			if ((currentOffer == null || !currentOffer.has("formattedPrice")) && offer != null) {
				json.put("oneTimePurchaseOfferDetails", new JSONObject()
					.put("formattedPrice", offer.getFormattedPrice())
					.put("priceAmountMicros", offer.getPriceAmountMicros())
					.put("priceCurrencyCode", offer.getPriceCurrencyCode()));
			}

			return json.toString();
		}
		catch (JSONException e) {
			Log.d("BILLING cannot read product details " + sku.getProductId() + ": " + e);
			return null;
		}
	}

	public static void querySkuDetails(final String[] ids) {
		Extension.mainActivity.runOnUiThread(new Runnable() 
		{
			public void run()
			{
				ArrayList<QueryProductDetailsParams.Product> products = new ArrayList<>();
				for (String productId : ids) {
					products.add(QueryProductDetailsParams.Product.newBuilder()
						.setProductId(productId)
						.setProductType("inapp")
						.build());
				}
				
				InAppPurchase.billingManager.queryProductDetailsAsync(products);

				Log.d("BILLING querySkuDetails start");

				new Handler().postDelayed(new Runnable() {
					@Override
					public void run() {
						
						Log.d("BILLING querySkuDetails fire after 5 sec " + InAppPurchase.complete);

						if (!InAppPurchase.complete)
							fireCallback("onRequestProductDataComplete", new Object[] { "Failure" });
						
					}
				}, 5000);
			}
			
		});
	}
	
	public static String getPublicKey () {
		return publicKey;
	}
	
	public static void initialize (final String publicKey, final HaxeObject callback) {
		Log.i("BILLING Initializing billing service");
		
		InAppPurchase.publicKey = publicKey;
		InAppPurchase.callback = callback;

		setupBillingManager();
	}

	private static void setupBillingManager()
	{
		Extension.mainActivity.runOnUiThread(new Runnable() 
		{
			public void run()
			{
				Log.i("BILLING Initializing billing service");
				if (InAppPurchase.billingManager == null)
				{
					InAppPurchase.updateListener = new UpdateListener();
					BillingManager.BASE_64_ENCODED_PUBLIC_KEY = InAppPurchase.publicKey;
					InAppPurchase.billingManager = new BillingManager(Extension.mainActivity, InAppPurchase.updateListener);
				}
				InAppPurchase.billingManager.queryPurchases();
			}
		});
	}
	
	public static void queryInventory() {
		Extension.mainActivity.runOnUiThread(new Runnable() 
		{
			public void run()
			{
				if (InAppPurchase.billingManager != null) {
					InAppPurchase.billingManager.queryInventory();
				}
			}
		});
	}
	
	@Override public void onDestroy () {
		if (InAppPurchase.billingManager != null) 
		{
			InAppPurchase.billingManager.destroy();
			InAppPurchase.billingManager = null;
		}
	}

	public static void cleanup() {}
	
	public static void setPublicKey (String s) {
		publicKey = s;
		BillingManager.BASE_64_ENCODED_PUBLIC_KEY = publicKey;
	}
}
