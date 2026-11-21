/* autonomous_host_dispatch, the function libautonomous calls, handed to Go. */
#include "autonomous.h"
#include "_cgo_export.h"

const char *autonomous_host_dispatch(const char *tool_id, const char *args_json) {
  return goAutonomousDispatch((char *)tool_id, (char *)args_json);
}
