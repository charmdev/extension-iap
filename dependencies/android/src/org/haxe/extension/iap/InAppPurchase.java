package org.haxe.extension.iap;

import android.opengl.GLSurfaceView;

import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClient.SkuType;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.Purchase.PurchaseState;
import com.android.billingclient.api.SkuDetails;

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

	private static class UpdateListener implements BillingUpdatesListener {
		@Override
		public void onBillingClientSetupFinished(final Boolean success) {
			if (success) {
				fireCallback("onStarted", new Object[] { "Success" });
			}
			else {
				fireCallback("onStarted", new Object[] { "Failure" });
			}
		}

		@Override
		public void onConsumeFinished(String token, final BillingResult result) {
			Log.d("Consumption finished. Purchase token: " + token + ", result: " + result);
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
			Log.d("Consumption finished. Purchase token: " + token + ", result: " + result);
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
			Log.d("onPurchasesUpdated: " + result);
			if (result.getResponseCode() == BillingResponseCode.OK)
			{
				for (Purchase purchase : purchaseList) 
				{
					if(purchase.getPurchaseState() == PurchaseState.PURCHASED) {
						//String sku = purchase.getSku();
						fireCallback("onPurchase", new Object[]{purchase.getOriginalJson(), "", purchase.getSignature()});
					}
				}
			}
			else
			{
				if (result.getResponseCode() ==  BillingResponseCode.USER_CANCELED)
				{
					fireCallback("onCanceledPurchase", new Object[] { "canceled" });
				}
				else
				{
					String message = "{\"result\":{\"message\":\"" + result + "\"}}";
					Log.d("onFailedPurchase: " + message);
					fireCallback("onFailedPurchase", new Object[] { (message) });
				}
			}
		}

		@Override
		public void onQuerySkuDetailsFinished(List<SkuDetails> skuList, final BillingResult result) {
			Log.d("onQuerySkuDetailsFinished: result: " + result.getDebugMessage());
			if (result.getResponseCode() == BillingResponseCode.OK) {
				String jsonResp =  "{ \"products\":[ ";
				for (SkuDetails sku : skuList) {
						jsonResp += sku.getOriginalJson() + ",";
				}
				jsonResp = jsonResp.substring(0, jsonResp.length() - 1);
				jsonResp += "]}";
				Log.d("onQuerySkuDetailsFinished: " + jsonResp + ", result: " + result.getDebugMessage());
				fireCallback("onRequestProductDataComplete", new Object[] { jsonResp });
			}
			else {
				fireCallback("onRequestProductDataComplete", new Object[] { "Failure" });
			}
		}

		@Override
		public void onQueryPurchasesFinished(List<Purchase> purchaseList) {
			String jsonResp =  "{ \"purchases\":[ ";
			for (Purchase purchase : purchaseList) {
				if(purchase.getPurchaseState() == PurchaseState.PURCHASED) {
					for(String sku : purchase.getSkus()){
						jsonResp += "{" +
								"\"key\":\"" + sku +"\", " +
								"\"value\":" + purchase.getOriginalJson() + "," +
								"\"itemType\":\"\"," +
								"\"signature\":\"" + purchase.getSignature() + "\"},";
					}
				}
			}
			jsonResp = jsonResp.substring(0, jsonResp.length() - 1);
			jsonResp += "]}";
			fireCallback("onQueryInventoryComplete", new Object[] { jsonResp });
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
					fireCallback("onFailedConsume", new Object[] {});
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
					fireCallback("onFailedAcknowledgePurchase", new Object[] {});
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

	public static void querySkuDetails(final String[] ids) {
		Extension.mainActivity.runOnUiThread(new Runnable() 
		{
			public void run()
			{
				InAppPurchase.billingManager.querySkuDetailsAsync(SkuType.INAPP, Arrays.asList(ids));
			}
		});
	}
	
	public static String getPublicKey () {
		return publicKey;
	}
	
	public static void initialize (final String publicKey, final HaxeObject callback) {
		Log.i ("Initializing billing service");
		
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
				Log.i ("Initializing billing service");
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