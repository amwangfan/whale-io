#!/system/bin/sh
export PATH=/system/bin:/system/xbin:${PATH}
/system/bin/am broadcast -a com.nova.fakecall.ACTION_HANGUP \
  -n com.nova.fakecall/.RingReceiver
/system/bin/am start -a android.intent.action.MAIN -c android.intent.category.HOME
echo "STOP / HOME"
