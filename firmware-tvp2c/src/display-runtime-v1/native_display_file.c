#include "native_display_file.h"
#include "native_display_page.h"   /* tdp_native_reply_now (V5 guard receipt) */
#include "display_carrier.h"
#include "display_memory.h"
static uint32_t u32(const uint8_t *p){return p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);}
static bool envelope(const uint8_t *p,size_t n){
 return p&&n>=32&&n<=TDP_NATIVE_PACKET_MAX&&!memcmp(p,"TDP1",4)&&p[4]==1&&
  p[5]>=TDP_QUERY&&p[5]<=TDP_STATUS&&!p[6]&&!p[7]&&u32(p+12)&&
  u32(p+24)==n-32&&u32(p+28)==tdp_crc(p+32,n-32);
}
TIOImageResult tdp_native_file_receive(const TIONativeFile *file,TIOCopyEnqueue enqueue){
 static const char name[]="turbo-display.tdp";
 if(!file||memcmp(file->filename,name,sizeof name))return TIO_FOREIGN;
 if(file->complete!=1||file->declared!=file->received||file->received<32||file->received>TDP_NATIVE_PACKET_MAX){
  /* V5 guard receipt: offer-once answer (kills its 5s UNCERTAIN + 60s gate quarantine). */
  uint32_t req=file->data&&file->received>=16?u32(file->data+12):0;
  tdp_native_offer_now(TDP_BAD_PACKET,req);return TIO_BAD_SIZE;
 }
 if(!envelope(file->data,file->received))return TIO_BAD_HEADER;
 if(!enqueue)return TIO_UI_FAILED;
 TIONativeMessage m={.id=TDP_NATIVE_MESSAGE_ID,.data=file->data,.bytes=file->received,.mode=0};
 return enqueue(1,&m)==0?TIO_OK:TIO_BUSY;
}
bool tdp_native_message_is_ours(const TIONativeMessage *m){return m&&m->id==TDP_NATIVE_MESSAGE_ID;}
TDPResult tdp_native_dispatch(TDPNativePage *page,const TIONativeMessage *m){
 if(!tdp_native_message_is_ours(m)||m->mode||m->reserved||m->context||m->padding[0]||m->padding[1]||m->padding[2])return TDP_BAD_PACKET;
 if(!envelope(m->data,m->bytes)){
  /* V5 guard receipt: an oversized/malformed TDP packet gets an offer-once BAD_PACKET
   * (with the request id when the header is readable) instead of a silent drop. */
  uint32_t req=m->data&&m->bytes>=16?u32(m->data+12):0;
  tdp_native_offer_now(TDP_BAD_PACKET,req);return TDP_BAD_PACKET;
 }
 return tdp_native_receive(page,m->data,m->bytes);
}
