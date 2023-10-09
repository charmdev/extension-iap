
package org.haxe.extension.iap.util;

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.FeatureType;
import com.android.billingclient.api.BillingClient.SkuType;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.PurchasesResponseListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * Handles all the interactions with Play Store (via Billing library), maintains connection to
 * it through BillingClient and caches temporary states/data if needed
 */
public class BillingManager implements PurchasesUpdatedListener {
	// Default value of mBillingClientResponseCode until BillingManager was not yeat initialized
	public static final int BILLING_MANAGER_NOT_INITIALIZED  = -1;

	private static final String TAG = "BillingManager hx:";
	private static final int MAX_RETRIES = 10;

	private static BillingResult errorResult = BillingResult.newBuilder().setResponseCode(BILLING_MANAGER_NOT_INITIALIZED).setDebugMessage("ERROR").build();

	/** A reference to BillingClient **/
	private BillingClient mBillingClient;

	/**
	 * True if billing service is connected now.
	 */
	private boolean mIsServiceConnected;

	private final BillingUpdatesListener mBillingUpdatesListener;

	private final Activity mActivity;

	private final List<Purchase> mPurchases = new ArrayList<>();

	private Set<String> mTokensToBeConsumed;
	private Set<String> mTokensToBeAcknowledged;
	
	private BillingClientStateListener stateListener;
	private ProductDetailsResponseListener productDetailsResponseListener;
	private int mReconnectAttempts = 0;
	private int mSkuRequestAttemts = 0;
	private int mSimulateFailuresUntilAttempt = 0;

	private int mBillingClientResponseCode = BILLING_MANAGER_NOT_INITIALIZED;

	private Map<String, ProductDetails> mSkuDetailsMap = new HashMap<>();

	/* BASE_64_ENCODED_PUBLIC_KEY should be YOUR APPLICATION'S PUBLIC KEY
	 * (that you got from the Google Play developer console). This is not your
	 * developer public key, it's the *app-specific* public key.
	 *
	 * Instead of just storing the entire literal string here embedded in the
	 * program,  construct the key at runtime from pieces or
	 * use bit manipulation (for example, XOR with some other string) to hide
	 * the actual key.  The key itself is not secret information, but we don't
	 * want to make it easy for an attacker to replace the public key with one
	 * of their own and then fake messages from the server.
	 */
	public static String BASE_64_ENCODED_PUBLIC_KEY = "CONSTRUCT_YOUR_KEY_AND_PLACE_IT_HERE";

	/**
	 * Listener to the updates that happen when purchases list was updated or consumption of the
	 * item was finished
	 */
	public interface BillingUpdatesListener {
		void onBillingClientSetupFinished(final Boolean success);
		void onQueryPurchasesFinished(List<Purchase> purchases);
		void onQueryPurchasesFailed();
		void onConsumeFinished(String token, BillingResult result);
		void onAcknowledgePurchaseFinished(String token, BillingResult result);
		void onPurchasesUpdated(List<Purchase> purchases, BillingResult result);
		void onQueryProductDetailsFinished(List<ProductDetails> productDetailsList, BillingResult result);
	}

	/**
	 * Listener for the Billing client state to become connected
	 */
	public interface ServiceConnectedListener {
		void onServiceConnected(BillingResult result);
	}

	public BillingManager(Activity activity, final BillingUpdatesListener updatesListener) {
		Log.d("BILLING  Creating Billing client.");
		mActivity = activity;
		mBillingUpdatesListener = updatesListener;
		mBillingClient = BillingClient.newBuilder(mActivity)
							.enablePendingPurchases()
							.setListener(this)
							.build();
	}

	/**
	 * Handle a callback that purchases were updated from the Billing library
	 */
	@Override
	public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
		if (result.getResponseCode() == BillingResponseCode.OK && purchases != null) {
			mPurchases.clear();
			for (Purchase purchase : purchases) {
				handlePurchase(purchase);
			}
			mBillingUpdatesListener.onPurchasesUpdated(mPurchases, result);
		} else {
			Log.w("BILLING onPurchasesUpdated() mPurchases: " + purchases);
			mBillingUpdatesListener.onPurchasesUpdated(purchases, result);
			Log.w("BILLING onPurchasesUpdated() got unknown resultCode: " + result);
		}
	}

	/**
	 * Start a purchase or subscription replace flow
	 */
	public void initiatePurchaseFlow(final String skuId) {
		final ProductDetails productDetails = mSkuDetailsMap.get(skuId);
		if(productDetails == null)
			return;

		Runnable purchaseFlowRequest = new Runnable() {
			@Override
			public void run() {
				Log.d("BILLING  Launching in-app purchase flow.");
				if (productDetails != null) {
					
					List<BillingFlowParams.ProductDetailsParams> productDetailsParamsList = Collections.singletonList(
					BillingFlowParams.ProductDetailsParams.newBuilder()
						.setProductDetails(productDetails)
						.build()
					);

					BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
							.setProductDetailsParamsList(productDetailsParamsList)
							.build();

					mBillingClient.launchBillingFlow(mActivity, billingFlowParams);
				}
			}
		};

		Runnable onError = new Runnable() {
			@Override
			public void run() {
			mBillingUpdatesListener.onPurchasesUpdated(null, errorResult);
		};
	};

		executeServiceRequest(purchaseFlowRequest, onError);
	}

	public Context getContext() {
		return mActivity;
	}

	/**
	 * Clear the resources
	 */
	public void destroy() {
		Log.d("BILLING  Destroying the manager.");

		if (mBillingClient != null && mBillingClient.isReady()) {
			mBillingClient.endConnection();
			mBillingClient = null;
		}
	}

	public void queryProductDetailsAsync(final ArrayList<QueryProductDetailsParams.Product> productList) {
		
		final Runnable queryRequest = new Runnable() {
			@Override
			public void run() {
				// Query the purchase async
				QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
						.setProductList(productList)
						.build();

				mBillingClient.queryProductDetailsAsync(params, productDetailsResponseListener);
			}
		};

		final Runnable onError = new Runnable() {
			@Override
			public void run() {
				Log.d("BILLING  onProductDetailsResponse onError: " + errorResult);
				mBillingUpdatesListener.onQueryProductDetailsFinished(null, errorResult);
			}
		};

		mSkuRequestAttemts = 0;
		productDetailsResponseListener = new ProductDetailsResponseListener() {
			@Override
			public void onProductDetailsResponse(BillingResult billingResult, List<ProductDetails> productDetailsList) {
				
				int responseCode = billingResult.getResponseCode();
				if (mSkuRequestAttemts < mSimulateFailuresUntilAttempt) {
					responseCode = 6;
				}

				Log.d("BILLING  onProductDetailsResponse code:" + responseCode);
				if ((responseCode != BillingResponseCode.OK) && (mSkuRequestAttemts < MAX_RETRIES)) {
					Log.d("BILLING  retry:" + mSkuRequestAttemts);
					mSkuRequestAttemts++;
					executeServiceRequest(queryRequest, onError);
				}
				else
				{
					mBillingUpdatesListener.onQueryProductDetailsFinished(productDetailsList, billingResult);
					if ((responseCode == BillingResponseCode.OK) && (productDetailsList != null)) {
						for (ProductDetails productDetails : productDetailsList) {

							mSkuDetailsMap.put(productDetails.getProductId(), productDetails);
						}
					}
				}
			}
		};

		executeServiceRequest(queryRequest, onError);
	}

	public void consumeAsync(final String purchaseToken) {
		// If we've already scheduled to consume this token - no action is needed (this could happen
		// if you received the token when querying purchases inside onReceive() and later from
		// onActivityResult()
		if (mTokensToBeConsumed == null) {
			mTokensToBeConsumed = new HashSet<>();
		} else if (mTokensToBeConsumed.contains(purchaseToken)) {
			Log.i("BILLING Token was already scheduled to be consumed - skipping...");
			return;
		}
		mTokensToBeConsumed.add(purchaseToken);

		final ConsumeParams consumeParams =
				ConsumeParams.newBuilder()
						.setPurchaseToken(purchaseToken)
						.build();

		// Generating Consume Response listener
		final ConsumeResponseListener onConsumeListener = new ConsumeResponseListener() {
			@Override
			public void onConsumeResponse(BillingResult billingResult, String purchaseToken) {
				// If billing service was disconnected, we try to reconnect 1 time
				// (feel free to introduce your retry policy here).
				mTokensToBeConsumed.remove(purchaseToken);
				mBillingUpdatesListener.onConsumeFinished(purchaseToken, billingResult);
			}
		};

		// Creating a runnable from the request to use it inside our connection retry policy below
		Runnable consumeRequest = new Runnable() {
			@Override
			public void run() {
				// Consume the purchase async
				Log.i("BILLING  Consuming:" + purchaseToken);
				mBillingClient.consumeAsync(consumeParams, onConsumeListener);
			}
		};

		Runnable onError = new Runnable() {
			@Override
			public void run() {
				mBillingUpdatesListener.onConsumeFinished(null, errorResult);
			}
		};

		executeServiceRequest(consumeRequest, onError);
	}

	public void acknowledgePurchase(final String purchaseToken) {
		// If we've already scheduled to consume this token - no action is needed (this could happen
		// if you received the token when querying purchases inside onReceive() and later from
		// onActivityResult()
		if (mTokensToBeAcknowledged == null) {
			mTokensToBeAcknowledged = new HashSet<>();
		} else if (mTokensToBeAcknowledged.contains(purchaseToken)) {
			Log.i("BILLING Token was already scheduled to be consumed - skipping...");
			return;
		}
		mTokensToBeAcknowledged.add(purchaseToken);

		final AcknowledgePurchaseParams acknowledgePurchaseParams =
				AcknowledgePurchaseParams.newBuilder()
						.setPurchaseToken(purchaseToken)
						.build();

		// Generating Consume Response listener
		final AcknowledgePurchaseResponseListener acknowledgePurchaseResponseListener = new AcknowledgePurchaseResponseListener() {
			@Override
			public void onAcknowledgePurchaseResponse(BillingResult billingResult) {
				// If billing service was disconnected, we try to reconnect 1 time
				// (feel free to introduce your retry policy here).
				mTokensToBeAcknowledged.remove(purchaseToken);
				mBillingUpdatesListener.onAcknowledgePurchaseFinished(purchaseToken, billingResult);
			}
		};

		// Creating a runnable from the request to use it inside our connection retry policy below
		Runnable acknowledgeRequest = new Runnable() {
			@Override
			public void run() {
				// Consume the purchase async
				Log.i("BILLING Consuming:" + purchaseToken);
				mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, acknowledgePurchaseResponseListener);
			}
		};

		Runnable onError = new Runnable() {
			@Override
			public void run() {
				mBillingUpdatesListener.onAcknowledgePurchaseFinished(null, errorResult);
			}
		};

		executeServiceRequest(acknowledgeRequest, onError);
	}

	/**
	 * Returns the value Billing client response code or BILLING_MANAGER_NOT_INITIALIZED if the
	 * clien connection response was not received yet.
	 */
	public int getBillingClientResponseCode() {
		return mBillingClientResponseCode;
	}

	/**
	 * Handles the purchase
	 * <p>Note: Notice that for each purchase, we check if signature is valid on the client.
	 * It's recommended to move this check into your backend.
	 * See {@link Security#verifyPurchase(String, String, String)}
	 * </p>
	 * @param purchase Purchase to be handled
	 */
	private void handlePurchase(Purchase purchase) {
		if (!verifyValidSignature(purchase.getOriginalJson(), purchase.getSignature())) {
			Log.i("BILLING Got a purchase: " + purchase + "; but signature is bad. Skipping...");
			return;
		}

		Log.d("BILLING Got a verified purchase: " + purchase);

		mPurchases.add(purchase);
	}

	/**
	 * Handle a result from querying of purchases and report an updated list to the listener
	 */
	
	private void onQueryPurchasesFinished(List<Purchase> result) {
		
		Log.d("BILLING Query inventory was successful.");
		
		mBillingUpdatesListener.onQueryPurchasesFinished(result);
		mBillingUpdatesListener.onBillingClientSetupFinished(true);
	}

	/**
	 * Checks if subscriptions are supported for current client
	 * <p>Note: This method does not automatically retry for RESULT_SERVICE_DISCONNECTED.
	 * It is only used in unit tests and after queryPurchases execution, which already has
	 * a retry-mechanism implemented.
	 * </p>
	 */
	public boolean areSubscriptionsSupported() {
		BillingResult response = mBillingClient.isFeatureSupported(FeatureType.SUBSCRIPTIONS);
		if (response.getResponseCode() != BillingResponseCode.OK) {
			Log.w("areSubscriptionsSupported() got an error response: " + response.getResponseCode());
		}
		return response.getResponseCode() == BillingResponseCode.OK;
	}

	/**
	 * Query purchases across various use cases and deliver the result in a formalized way through
	 * a listener
	 */
	public void queryPurchases() {
		Log.d("BILLING  Starting setup.");
		Runnable queryToExecute = new Runnable() {
			@Override
			public void run() {
				long time = System.currentTimeMillis();
				Log.i("BILLING Querying purchases elapsed time: " + (System.currentTimeMillis() - time) + "ms");

				mBillingClient.queryPurchasesAsync(
				QueryPurchasesParams.newBuilder()
					.setProductType("inapp")
					.build(),

					new PurchasesResponseListener() {
						@Override
						public void onQueryPurchasesResponse(BillingResult billingResult,
								List<Purchase> purchaseList) {
									onQueryPurchasesFinished(purchaseList);
						}
				});
			}
		};

		Runnable onError = new Runnable() {
			@Override
			public void run() {
				mBillingUpdatesListener.onBillingClientSetupFinished(false);
			}
		};
		executeServiceRequest(queryToExecute, onError);
	}
	
	public void queryInventory() {
		Log.d("BILLING Starting setup.");
		Runnable queryToExecute = new Runnable() {
			@Override
			public void run() {
				long time = System.currentTimeMillis();
				Log.i("BILLING Querying purchases elapsed time: " + (System.currentTimeMillis() - time) + "ms");
			}
		};

		Runnable onError = new Runnable() {
			@Override
			public void run() {
				mBillingUpdatesListener.onQueryPurchasesFailed();
			}
		};
		executeServiceRequest(queryToExecute, onError);
	}

	public void startServiceConnection(final Runnable executeOnSuccess, final Runnable executeOnError) {
		mReconnectAttempts = 0;

		stateListener = new BillingClientStateListener() {
			@Override
			public void onBillingSetupFinished(BillingResult billingResponse) {
				int billingResponseCode = billingResponse.getResponseCode();
				if (mReconnectAttempts < mSimulateFailuresUntilAttempt)
				{
					billingResponseCode = 6;
				}

				Log.d("BILLING Setup finished. Response code: " + billingResponseCode);
				mBillingClientResponseCode = billingResponseCode;

				if (billingResponseCode == BillingResponseCode.OK) {
					mIsServiceConnected = true;
					if (executeOnSuccess != null) {
						executeOnSuccess.run();
					}
				}
				else {
					if (executeOnError != null) {
						if (mReconnectAttempts >= MAX_RETRIES) {
							executeOnError.run();
						}
						else {
							mReconnectAttempts++;
							mBillingClient.startConnection(stateListener);
						}
					}
					mIsServiceConnected = false;
				}
			}

			@Override
			public void onBillingServiceDisconnected() {
				Log.d("BILLING OnBillingServiceDisconnected");
				mIsServiceConnected = false;
			}
		};

		mBillingClient.startConnection(stateListener);
	}

	private void executeServiceRequest(final Runnable runnable, Runnable onError) {
		if (mIsServiceConnected) {
			runnable.run();
		} else {
			// If billing service was disconnected, we try to reconnect 1 time.
			// (feel free to introduce your retry policy here).
			startServiceConnection(runnable, onError);
		}
	}

	/**
	 * Verifies that the purchase was signed correctly for this developer's public key.
	 * <p>Note: It's strongly recommended to perform such check on your backend since hackers can
	 * replace this method with "constant true" if they decompile/rebuild your app.
	 * </p>
	 */
	private boolean verifyValidSignature(String signedData, String signature) {
		try {
			return Security.verifyPurchase(BASE_64_ENCODED_PUBLIC_KEY, signedData, signature);
		} catch (IOException e) {
			Log.e("Got an exception trying to validate a purchase: " + e);
			return false;
		}
		
	}
}
