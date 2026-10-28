#!/system/bin/sh
# Answer active VoIP call and open InCall UI (starts audio bridge)
export PATH=/system/bin:/system/xbin:${PATH}
/system/bin/am broadcast -a com.nova.fakecall.ACTION_ANSWER \
  -n com.nova.fakecall/.RingReceiver
echo "ANSWER sent"
