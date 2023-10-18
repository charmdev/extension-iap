package extension.iap.android;

import extension.iap.IAP;
import extension.iap.EventDispatcher;
import flash.errors.Error;
import flash.events.Event;
import flash.Lib;
import haxe.Json;

#if (openfl < "4.0.0")
import openfl.utils.JNI;
#else
import lime.system.JNI;
#end

/**
 * Provides convenience methods and properties for in-app purchases (Android & iOS).
 * The methods and properties are static, so there's no need to instantiate an instance,
 * but an initialization is required prior to the first use.
 * Every method is asynchronous (non-blocking). The callbacks always fire events indicating
 * the success or failure of every operation.
 *
 * The first step is to initialize the extension. You do so by calling the {@link #initialize}
 * method. The result comes with a PURCHASE_INIT or PURCHASE_INIT_FAILED IAPEvent. Also, the
 * available property will tell if you can use the extension at any time.
 *
 * Although we aim to provide a unified API for every target, there are some differences that
 * required to leave platform exclusive methods and properties. So you'll find different workflows.
 *
 * Android workflow:
 * ----------------
 *
 * After initialization is complete, you will typically want to request an inventory of owned
 * items and subscriptions. See {@link #queryInventory} and related events. This method can also be
 * used to retrieve a detailed list of products.
 *
 * Then you may want to buy items with the {@link #purchase} method, and if the item is consumable,
 * the {@link #consume} method should be called after a successful purchase.
 *
 * iOS workflow:
 * ------------
 *
 * After initialization is complete, you will typically want request details about the products
 * being sold {@link #requestProductData}, and also probably try to restore non consumable
 * items previously purchased by the user using the {@link #restore} method.
 *
 * Then you may want to buy items with the {@link #purchase} method. You don't need to call the
 * consume method for iOS, but for consumable items you may want to locally erase the purchase from
 * the Inventory.
 *
 * You may want to check the IAPEvent, Purchase and ProductDetails classes to explore further.
 *
 */

@:allow(extension.iap) class IAP {

	public static var initCounter:Int = 0;

	public static var available (get, null):Bool;
	public static var manualTransactionMode (get, set):Bool;
	public static var inventory(default, null):Inventory = null;
	private static var initialized = false;

	// Event dispatcher composition
	private static var dispatcher = new EventDispatcher ();
	private static var iapHandler:IAPHandler = new IAPHandler();

	/**
	 * Initializes the extension.
	 *
	 * @param publicKey (Android). Your application's public key, encoded in base64.
	 *     This is used for verification of purchase signatures. You can find your app's base64-encoded
	 *     public key in your application's page on Google Play Developer Console. Note that this
	 *     is NOT your "developer public key".
	 *
	 * Related Events (IAPEvent):
	 * 		PURCHASE_INIT: Fired when the initialization was successful
	 * 		PURCHASE_INIT_FAILED: Fired when the initialization failed
	 */

	public static function initialize (publicKey:String = ""):Void {

		inventory = new Inventory(null);
		funcInit (publicKey, iapHandler);

		initialized = true;
	}

	public static function cleanup():Void {
		inventory = null;
		initialized = false;
		dispatcher.removeAllListeners();
		funcCleanup();
	}

	/**
	 * Sends a purchase intent for a given product.
	 *
	 * @param productID (iOS & Android). The unique Id for the desired product (Android Sku).
	 * @param devPayload (Android). Extra data (developer payload), which will be returned with the purchase data
	 *     when the purchase completes. This extra data will be permanently bound to that purchase
	 *     and will always be returned when the purchase is queried.
	 *
	 * Related Events (IAPEvent):
	 * 		PURCHASE_SUCCESS: Fired when the purchase attempt was successful
	 * 		PURCHASE_FAILURE: Fired when the purchase attempt failed
	 * 		PURCHASE_CANCEL: Fired when the purchase attempt was cancelled by the user
	 */

	public static function purchase(productID:String, devPayload:String = ""):Void {
		funcBuy(productID, devPayload);
	}

	/**
	 * Retrieves localized information about a list of products.
	 * 
	 * @param inArg. A String with the product Id, or an Array of Strings with multiple product Ids.
	 * 
	 * Related Events (IAPEvent): 
	 * 		PURCHASE_PRODUCT_DATA_COMPLETE: Fired when the products data has been retrieved. 
	 * 			The event will come with a productsData array.
	 * 			This method also populates the productDetailsMap property of the inventory, so it can be accessed anytime after calling it.
	 */
	
	public static function requestProductData(ids:Array<String>):Void {
		funcQuerySkuDetails(ids);
	}

	/**
	 * Sends a consume intent for a given product.
	 *
	 * @param purchase. The previously purchased product.
	 *
	 * Related Events (IAPEvent):
	 * 		PURCHASE_CONSUME_SUCCESS: Fired when the consume attempt was successful
	 * 		PURCHASE_CONSUME_FAILURE: Fired when the consume attempt failed
	 */

	public static function consume(purchase:Purchase):Void {
		funcConsume(purchase.originalJson, purchase.signature);
	}
	
	/**
	 * Sends a acknowledgePurchase intent for a given product.
	 *
	 * @param purchase. The previously purchased product.
	 *
	 * Related Events (IAPEvent):
	 * 		PURCHASE_ACKNOWLEDGE_SUCCESS: Fired when the acknowledgePurchase attempt was successful
	 * 		PURCHASE_ACKNOWLEDGE_FAILURE: Fired when the acknowledgePurchase attempt failed
	 */

	public static function acknowledgePurchase (purchase:Purchase):Void {
		funcAcknowledgePurchase (purchase.originalJson, purchase.signature);
	}

	public static function queryInventory ():Void {
		funcQueryInventory();
	}

	// Getter & Setter Methods


	private static function get_available ():Bool {

		return true;

	}

	private static function get_manualTransactionMode ():Bool {

		return false;

	}

	private static function set_manualTransactionMode (val:Bool):Bool {

		return false;

	}

	// Event Dispatcher composition methods

	public static function addEventListener (type:String, listener:IAPEvent->Void):Void {

		dispatcher.setListener(type, listener);
	}

	public static function removeEventListener (type:String):Void {

		dispatcher.removeListener(type);

	}

	public static function dispatchEvent (event:IAPEvent):Void {
		dispatcher.dispatchEvent (event);
	}
	

	// Native Methods
	static var funcInit = JNI.createStaticMethod("org/haxe/extension/iap/InAppPurchase", "initialize", "(Ljava/lang/String;Lorg/haxe/lime/HaxeObject;)V");
	static var funcBuy = JNI.createStaticMethod ("org/haxe/extension/iap/InAppPurchase", "buy", "(Ljava/lang/String;Ljava/lang/String;)V");
	static var funcQuerySkuDetails = JNI.createStaticMethod ("org/haxe/extension/iap/InAppPurchase", "querySkuDetails", "([Ljava/lang/String;)V");
	static var funcConsume = JNI.createStaticMethod ("org/haxe/extension/iap/InAppPurchase", "consume", "(Ljava/lang/String;Ljava/lang/String;)V");
	static var funcAcknowledgePurchase = JNI.createStaticMethod ("org/haxe/extension/iap/InAppPurchase", "acknowledgePurchase", "(Ljava/lang/String;Ljava/lang/String;)V");
	static var funcQueryInventory = JNI.createStaticMethod("org/haxe/extension/iap/InAppPurchase", "queryInventory", "()V");
	static var funcCleanup = JNI.createStaticMethod("org/haxe/extension/iap/InAppPurchase", "cleanup", "()V");
}


#if (android && !display)


private class IAPHandler {

	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	public function new () { }

	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	public function onCanceledPurchase (message:String):Void {
		IAP.dispatchEvent (new IAPEvent (IAPEvent.PURCHASE_CANCEL));
	}

	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	public function onFailedConsume (response:String):Void {
		var evt:IAPEvent = new IAPEvent (IAPEvent.PURCHASE_CONSUME_FAILURE);
		
		try {
			var dynResp:Dynamic = Json.parse(response);
			evt.productID = Reflect.field(Reflect.field(dynResp, "product"), "productId");
			evt.message = Reflect.field(Reflect.field(dynResp, "result"), "message");
		}
		catch (e:Dynamic)
		{
			evt.productID = "";
			evt.message = "Caught JSONException while trying to consume product";
		}
		
		IAP.dispatchEvent (evt);
	}

	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	public function onConsume (response:String):Void {
		trace('onConsume: $response');

		var dynResp:Dynamic = Json.parse(response);
		var evt:IAPEvent = new IAPEvent (IAPEvent.PURCHASE_CONSUME_SUCCESS);
		evt.productID = Reflect.field(dynResp, "productId");		
		IAP.dispatchEvent(evt);
	}
	
	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	public function onFailedAcknowledgePurchase (response:String):Void {
		var evt:IAPEvent = new IAPEvent (IAPEvent.PURCHASE_ACKNOWLEDGE_FAILURE);
		
		try {
			var dynResp:Dynamic = Json.parse(response);
			evt.productID = Reflect.field(Reflect.field(dynResp, "product"), "productId");
			evt.message = Reflect.field(Reflect.field(dynResp, "result"), "message");
		}
		catch (e:Dynamic)
		{
			evt.productID = "";
			evt.message = "Caught JSONException while trying to acknowledge purchase";
		}
		
		IAP.dispatchEvent (evt);
	}

	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	public function onAcknowledgePurchase (response:String):Void {
		trace('onAcknowledgePurchase: $response');

		var dynResp:Dynamic = Json.parse(response);
		var evt:IAPEvent = new IAPEvent (IAPEvent.PURCHASE_ACKNOWLEDGE_SUCCESS);
		evt.productID = Reflect.field(dynResp, "productId");		
		IAP.dispatchEvent(evt);
	}

	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	public function onFailedPurchase (response:String):Void {
		var dynResp:Dynamic = Json.parse(response);
		var evt:IAPEvent = new IAPEvent (IAPEvent.PURCHASE_FAILURE);
		if (Reflect.field(dynResp, "product") != null) evt.productID = Reflect.field(Reflect.field(dynResp, "product"), "productId");
		evt.message = Reflect.field(Reflect.field(dynResp, "result"), "message");
		IAP.dispatchEvent (evt);
	}

	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	public function onPurchase(response:String, itemType:String, signature:String):Void {
		var evt:IAPEvent = new IAPEvent(IAPEvent.PURCHASE_SUCCESS);

		evt.purchase = new Purchase(response, itemType, signature, Purchase.PURCHASE_STATE_PURCHASED);
		evt.productID = evt.purchase.productID;
		IAP.inventory.addPurchase(evt.purchase);
		IAP.dispatchEvent(evt);
	}
	
	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	public function onPending(response:String, itemType:String, signature:String):Void {
		var evt:IAPEvent = new IAPEvent(IAPEvent.PURCHASE_PENDING);

		evt.purchase = new Purchase(response, itemType, signature, Purchase.PURCHASE_STATE_PENDING);
		evt.productID = evt.purchase.productID;
		IAP.inventory.addPending(evt.purchase);
		IAP.dispatchEvent(evt);
	}

	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	public function onRequestProductDataComplete(response:String):Void {

		if (response == "Failure") {
			IAP.dispatchEvent (new IAPEvent (IAPEvent.PURCHASE_PRODUCT_DATA_FAILED));

		} else {

			var dynResp:Dynamic = Json.parse(response);
			var evt:IAPEvent = new IAPEvent (IAPEvent.PURCHASE_PRODUCT_DATA_COMPLETE);
			evt.productsData = new Array<IAProduct>();

			var dynDescriptions:Array<Dynamic> = Reflect.field(dynResp, "products");
			var dynItmValue:Dynamic;
			var prod:IAProduct;

			if (dynDescriptions != null) {
				for (dynItm in dynDescriptions) {
					
					prod = { productID: Reflect.field(dynItm, "productId") };
					prod.type = Reflect.field(dynItm, "type");

					var oneTimePurchaseOfferDetails:Dynamic = Reflect.field(dynItm, "oneTimePurchaseOfferDetails");
					prod.localizedPrice = Reflect.field(oneTimePurchaseOfferDetails, "formattedPrice");
					prod.priceAmountMicros = Reflect.field(oneTimePurchaseOfferDetails, "priceAmountMicros");
					prod.priceCurrencyCode = Reflect.field(oneTimePurchaseOfferDetails, "priceCurrencyCode");
					
					prod.price = prod.priceAmountMicros / 1000 / 1000;
					prod.localizedTitle = Reflect.field(dynItm, "title");
					prod.localizedDescription = Reflect.field(dynItm, "description");
					evt.productsData.push(prod);
				}
			}

			IAP.dispatchEvent (evt);
		}
	}

	public function onQueryInventoryComplete(response:String):Void {
		trace('onQueryInventoryComplete');

		var dynResp:Dynamic = Json.parse(response);
		IAP.inventory = new Inventory(dynResp);
		IAP.dispatchEvent (new IAPEvent (IAPEvent.PURCHASE_QUERY_INVENTORY_SUCCESS));
	}
	
	public function onQueryInventoryFailed(response:String):Void {
		trace('onQueryInventoryFailed');

		IAP.inventory = new Inventory(null);
		IAP.dispatchEvent (new IAPEvent (IAPEvent.PURCHASE_QUERY_INVENTORY_FAILURE));
	}

	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

	
	public function onStarted (response:String):Void {
		if (response == "Success") {
			IAP.dispatchEvent (new IAPEvent (IAPEvent.PURCHASE_INIT));
		} else {
			IAP.dispatchEvent (new IAPEvent (IAPEvent.PURCHASE_INIT_FAILED));
		}
	}

	public function log(message:String):Void {
		var event:IAPEvent = new IAPEvent (IAPEvent.LOG);
		event.message = message;
		IAP.dispatchEvent(event);
	}
	///////////////////////////////////////////////////////////////////////////////////////////
	///////////////////////////////////////////////////////////////////////////////////////////

}

#end
