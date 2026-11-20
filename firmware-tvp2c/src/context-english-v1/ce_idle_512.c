/* 1.0.5.12 replacement for coexist.S (hand-ported 1.0.5.5 assembly).
 *
 * The 5.5 assembly compared nav_top_app() against two data addresses
 * (0x18052201/0x1805240d) in the pre-AP flash region. In 1.0.5.12 that region
 * shifted (the stock reference string moved 0x1805318b -> 0x1805380c), so those
 * literals match nothing and the function always reported busy, gating every
 * module feature through dots_business_idle()/home().
 *
 * This C version uses only the linked, runtime-proven nav_top_app() getter and
 * a module-local copy of the launcher id. The CE overlay never changes the top
 * app (it renders inside the launcher), so "idle iff the top app is the
 * launcher" preserves the intent - block module features while a stock
 * foreground page owns the screen - without any fragile data addresses.
 */
#include <string.h>
#include <stdbool.h>

extern const char *nav_top_app(void);

bool ce_extension_idle(void){
    const char *s = nav_top_app();
    if (!s || !s[0]) return true;
    return !strcmp(s, "com.rayneo.liteos.launcher");
}
