package org.haxe.extension.iap.util;

import org.haxe.lime.HaxeObject;
import org.haxe.extension.Extension;
import android.opengl.GLSurfaceView;

public class Log
{
	private static HaxeObject callback = null;
	private static final String TAG = "BillingManager hx:";

	public static void initialize (final HaxeObject callback)
	{
		Log.callback = callback;
	}

	public static void d(final String message)
	{
		android.util.Log.d(TAG, message);
		if (Log.callback != null)
		{
			fireCallback("log", new Object[] { (message) });
		}
	}

	public static void e(final String message)
	{
		android.util.Log.e(TAG, message);
		if (Log.callback != null)
		{
			fireCallback("log", new Object[] { (message) });
		}
	}

	public static void i(final String message)
	{
		android.util.Log.i(TAG, message);
		if (Log.callback != null)
		{
			fireCallback("log", new Object[] { (message) });
		}
	}

	public static void w(final String message)
	{
		android.util.Log.w(TAG, message);
		if (Log.callback != null)
		{
			fireCallback("log", new Object[] { (message) });
		}
	}

	private static void fireCallback(final String name, final Object[] payload)
	{
		if (Extension.mainView == null) return;
		GLSurfaceView view = (GLSurfaceView) Extension.mainView;

		view.queueEvent(new Runnable()
		{
			public void run()
			{
				if (Log.callback != null)
				{
					Log.callback.call(name, payload);
				}
			}
		});
	}
}
