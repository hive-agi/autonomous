/* The dispatchers libautonomous calls, handed to Go. Both are exported: a
 * 0.3 library calls autonomous_host_dispatch_v2 with the caller's grant, a
 * 0.2 library calls autonomous_host_dispatch without one. A process must
 * hold only one definition of each (see autonomous.h). */
#include "autonomous.h"
#include "_cgo_export.h"

const char *autonomous_host_dispatch_v2(const char *tool_id, const char *args_json,
                                        const char *grant_json) {
  return goAutonomousDispatch((char *)tool_id, (char *)args_json, (char *)grant_json, 1);
}

const char *autonomous_host_dispatch(const char *tool_id, const char *args_json) {
  return goAutonomousDispatch((char *)tool_id, (char *)args_json, NULL, 0);
}
