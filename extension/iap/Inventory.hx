package extension.iap;

/**
 * Represents a block of information about in-app items.
 * An Inventory is returned by such methods as {@link IAP#queryInventory}.
 */

class Inventory
{

	public var productDetailsMap(default, null):Map<String, ProductDetails>;
	public var purchaseMap(default, null):Map<String, Purchase>; // TODO: replace this map with `purchases`
	
	public var purchases(default, null):Map<String, Array<Purchase>>;
	public var pendingPurchases(default, null):Array<Purchase>;
	
	public function new(?dynObj:Dynamic) 
	{
		productDetailsMap = new Map();
		purchaseMap = new Map();
		
		purchases = new Map<String, Array<Purchase>>();
		pendingPurchases = [];
		
		if (dynObj != null) {
			
			var dynDescriptions:Array<Dynamic> = Reflect.field(dynObj, "descriptions");
			if (dynDescriptions != null) {
				
				for (dynItm in dynDescriptions) {
					productDetailsMap.set(cast Reflect.field(dynItm, "key"), new ProductDetails(Reflect.field(dynItm, "value")));
				}
				
			}
			
			var dynPurchases:Array<Dynamic> = Reflect.field(dynObj, "purchases");
			if (dynPurchases != null) {
				
				for (dynItm in dynPurchases) {
					var purchaseState:Null<Int> = Purchase.PURCHASE_STATE_PURCHASED;
					
				#if android
					purchaseState = Reflect.field(dynItm, "purchaseState");
				#end
					
					var p = new Purchase(Reflect.field(dynItm, "value"), Reflect.field(dynItm, "itemType"), Reflect.field(dynItm, "signature"), purchaseState);
					
					if (p.purchaseState == Purchase.PURCHASE_STATE_PURCHASED)
					{
						purchaseMap.set(cast Reflect.field(dynItm, "key"), p);
						addPurchase(p);
					}
					else if (p.purchaseState == Purchase.PURCHASE_STATE_PENDING)
					{
						pendingPurchases.push(p);
					}
				}
			}
		}
	}
	
	/** Returns the listing details for an in-app product. */
	public function getProductDetails(productId:String) :ProductDetails {
		return productDetailsMap.get(productId);
	}
	
	/** Returns purchase information for a given product, or null if there is no purchase. */
    public function getPurchase(productId:String) :Purchase {
        return purchaseMap.get(productId);
    }

    /** Returns whether or not there exists a purchase of the given product. */
    public function hasPurchase(productId:String) :Bool {
        return purchaseMap.exists(productId);
    }

    /** Return whether or not details about the given product are available. */
    public function hasDetails(productId:String) :Bool {
        return productDetailsMap.exists(productId);
    }

    /**
     * Erase a purchase (locally) from the inventory, given its product ID. This just
     * modifies the Inventory object locally and has no effect on the server! This is
     * useful when you have an existing Inventory object which you know to be up to date,
     * and you have just consumed an item successfully, which means that erasing its
     * purchase data from the Inventory you already have is quicker than querying for
     * a new Inventory.
     */
    public function erasePurchase(productId:String):Void {
		// TODO: remove this method???
        if (purchaseMap.exists(productId)) {
			purchaseMap.remove(productId);
		}
    }
	
	public function addPurchase(purchase:Purchase):Void {
		if (!purchases.exists(purchase.productID))
		{
			purchases.set(purchase.productID, []);
		}
		
		purchases.get(purchase.productID).push(purchase);
		
		removePendingPurchase(purchase);
	}
	
	public function removePurchase(purchase:Purchase):Void {
		if (purchases.exists(purchase.productID)) {
			var purchaseToRemove:Purchase = null;
			
			for (p in purchases.get(purchase.productID)) {
				if (p.purchaseID == purchase.purchaseID && p.purchaseDate == purchase.purchaseDate) {
					purchaseToRemove = p;
					break;
				}
			}
			
			if (purchaseToRemove != null) {
				purchases.get(purchase.productID).remove(purchaseToRemove);
			}
		}
	}
	
	public function removePendingPurchase(purchase:Purchase):Void {
		var purchaseToRemove:Purchase = null;
		for (p in pendingPurchases) {
			if (p.purchaseID == purchase.purchaseID && p.purchaseDate == purchase.purchaseDate) {
				purchaseToRemove = p;
				break;
			}
		}
		
		if (purchaseToRemove != null) {
			pendingPurchases.remove(purchaseToRemove);
		}
	}
	
}
