#pragma once
// A per-unit header is injected only by the factory build environment.
#ifdef SMARTPLUG_FACTORY_PROVISIONED
static_assert(sizeof(SMARTPLUG_FACTORY_AP_PASSWORD) >= 13, "Factory AP password too short");
static_assert(sizeof(SMARTPLUG_FACTORY_ADMIN_PASSWORD) >= 13, "Factory admin password too short");
#endif
