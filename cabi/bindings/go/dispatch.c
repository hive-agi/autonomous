/* The one function libautonomous calls by name. cgo exports Go functions
 * with non-const C types, so this shim keeps the header's const signature
 * and hands the call to Go. */
#include "autonomous.h"
#include "_cgo_export.h"

const char *autonomous_host_dispatch(const char *tool_id, const char *args_json) {
  return goAutonomousDispatch((char *)tool_id, (char *)args_json);
}
