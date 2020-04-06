package extension.iap;

typedef Listener = IAPEvent -> Void;

class EventDispatcher 
{
	var listeners:Map<String, Listener>;

	public function new()
	{
		listeners = new Map<String, Listener>();
	}

	public function setListener(type:String, listener:Listener):Void
	{
		listeners.set(type, listener);
	}

	public function removeListener(type:String):Void
	{
		listeners.remove(type);
	}

	public function dispatchEvent(event:IAPEvent):Void
	{
		var listener = listeners.get(event.type);
		if (listener != null) 
		{
			listener(event);
		}
	}

	public function removeAllListeners() 
	{
		for (k in listeners.keys()) 
		{
			listeners.remove(k);
		}
	}
}
