/*
 * sparc/netbsd1/md.h
 * NetBSD1 sparc configuration information.
 *
 * Copyright (c) 1996, 1997
 *	Transvirtual Technologies, Inc.  All rights reserved.
 *
 * See the file "license.terms" for information on usage and redistribution 
 * of this file. 
 */

#ifndef __sparc_netbsd1_md_h
#define __sparc_netbsd1_md_h

#include "sparc/common.h"
#include "sparc/threads.h"

#define SIGNAL_ARGS(sig, sc) int sig, int code, struct sigcontext* sc
#define SIGNAL_CONTEXT_POINTER(scp) struct sigcontext* scp
#define GET_SIGNAL_CONTEXT_POINTER(scp) (scp)
#define SIGNAL_PC(scp) ((scp)->sc_pc)
#define STACK_POINTER(scp) ((scp)->sc_sp)

#if defined(TRANSLATOR)
#include "jit-md.h"
#endif

#undef SP_OFFSET
#define SP_OFFSET 2

#endif
