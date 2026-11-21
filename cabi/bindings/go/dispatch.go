package autonomous

// The library calls autonomous_host_dispatch (dispatch.c), which calls
// goAutonomousDispatch here, on the runtime's thread.

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"unsafe"
)

// last is the result of the previous dispatch, freed at the next one.
var last *C.char

func errorJSON(msg string) []byte {
	b, _ := json.Marshal(map[string]any{"error": map[string]string{"message": msg}})
	return b
}

func run(tool string, argsJSON string) []byte {
	h, ok := handlers[tool]
	if !ok {
		return errorJSON("no handler for " + tool)
	}
	var args map[string]any
	if err := json.Unmarshal([]byte(argsJSON), &args); err != nil {
		return errorJSON("arguments are not a JSON object: " + err.Error())
	}
	var (
		out any
		err error
	)
	func() {
		defer func() {
			if r := recover(); r != nil {
				err = fmt.Errorf("handler panicked: %v", r)
			}
		}()
		out, err = h(args)
	}()
	if err != nil {
		return errorJSON(err.Error())
	}
	b, err := json.Marshal(out)
	if err != nil {
		return errorJSON("result is not JSON: " + err.Error())
	}
	return b
}

//export goAutonomousDispatch
func goAutonomousDispatch(tool, args *C.char) *C.char {
	out := C.CString(string(run(C.GoString(tool), C.GoString(args))))
	if last != nil {
		C.free(unsafe.Pointer(last))
	}
	last = out
	return out
}
