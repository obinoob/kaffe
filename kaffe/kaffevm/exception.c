/*
 * exception.c
 * Handle exceptions for the interpreter or translator.
 *
 * Copyright (c) 1996, 1997
 *	Transvirtual Technologies, Inc.  All rights reserved.
 *
 * See the file "license.terms" for information on usage and redistribution 
 * of this file. 
 */

#include "config.h"
#include "debug.h"
#include "config-std.h"
#include "config-signal.h"
#include "config-mem.h"
#include "config-setjmp.h"
#include "jtypes.h"
#include "access.h"
#include "object.h"
#include "constants.h"
#include "classMethod.h"
#include "code.h"
#include "exception.h"
#include "baseClasses.h"
#include "lookup.h"
#include "thread.h"
#include "jthread.h"
#include "errors.h"
#include "itypes.h"
#include "external.h"
#include "soft.h"
#include "md.h"
#include "locks.h"
#include "stackTrace.h"
#include "machine.h"
#include "slots.h"

#if defined(INTERPRETER)
struct _exceptionFrame { };
#define	FIRSTFRAME(f, e)	/* Does nothing */
#elif defined(TRANSLATOR)
static Method* findExceptionInMethod(uintp, Hjava_lang_Class*, exceptionInfo*);
#endif	/* TRANSLATOR */

static void nullException(struct _exceptionFrame *);
static void floatingException(struct _exceptionFrame *);
static void dispatchException(Hjava_lang_Throwable*, struct _exceptionFrame*) __NORETURN__;

Hjava_lang_Object* buildStackTrace(struct _exceptionFrame*);

extern uintp Kaffe_JNI_estart;
extern uintp Kaffe_JNI_eend;
extern void Kaffe_JNIExceptionHandler(void);

static bool findExceptionBlockInMethod(uintp, Hjava_lang_Class*, Method*, exceptionInfo*);

/*
 * Throw an internal exception.
 */
void
throwException(Hjava_lang_Throwable* eobj)
{
	if (eobj != 0) {
		unhand(eobj)->backtrace = buildStackTrace(0);
	}
	throwExternalException(eobj);
}

/*
 * Create an exception from error information.
 */
Hjava_lang_Throwable* 
error2Throwable(errorInfo* einfo)
{
	Hjava_lang_Throwable *err = 0;

	switch (einfo->type & KERR_CODE_MASK) {
	case KERR_EXCEPTION:
		err = (Hjava_lang_Throwable*)execute_java_constructor(
			    einfo->classname, 
			    0, "(Ljava/lang/String;)V",
			    checkPtr(stringC2Java(einfo->mess)));
		break;

	case KERR_RETHROW:
		err = einfo->throwable;
		break;

	case KERR_INITIALIZER_ERROR:
		err = (Hjava_lang_Throwable*)execute_java_constructor(
			    JAVA_LANG(ExceptionInInitializerError),
			    0, "(Ljava/lang/Throwable;)V", einfo->throwable);
		break;

	case KERR_OUT_OF_MEMORY:
		err = gc_throwOOM();
		break;
	}

	discardErrorInfo(einfo);
	return (err);
}

/*
 * post out-of-memory condition
 */
void 
postOutOfMemory(errorInfo *einfo)
{
	memset(einfo, 0, sizeof(*einfo));
    	einfo->type = KERR_OUT_OF_MEMORY;
}

/*
 * post a simple exception using its full name without a message
 */
void 
postException(errorInfo *einfo, const char *name)
{
	einfo->type = KERR_EXCEPTION;
	einfo->classname = name;
	einfo->mess = "";
	einfo->throwable = 0;
}

void 
vpostExceptionMessage(errorInfo *einfo,
	const char * fullname, const char * fmt, va_list args)
{
        char *msgBuf;
        int msgLen;

	msgBuf = KMALLOC(MAX_ERROR_MESSAGE_SIZE);
	if (msgBuf == 0) {
		einfo->type = KERR_OUT_OF_MEMORY;
		return;
	}

#ifdef HAVE_VSNPRINTF
        msgLen = vsnprintf(msgBuf, MAX_ERROR_MESSAGE_SIZE, fmt, args);
#else
        /* XXX potential buffer overruns problem: */
        msgLen = vsprintf(msgBuf, fmt, args);
#endif
	einfo->type = KERR_EXCEPTION | KERR_FREE_MESSAGE;
	einfo->classname = fullname;
	einfo->mess = msgBuf;
	einfo->throwable = 0;
}

/*
 * post a longer exception with a message using full name
 */
void 
postExceptionMessage(errorInfo *einfo,
	const char * fullname, const char * fmt, ...)
{
        va_list args;

        va_start(args, fmt);
	vpostExceptionMessage(einfo, fullname, fmt, args);
        va_end(args);
}

/*
 * dump error info to stderr
 */
void
dumpErrorInfo(errorInfo *einfo)
{
	/* XXX */
}

/*
 * discard the errorinfo, freeing a message if necessary
 */
void 
discardErrorInfo(errorInfo *einfo)
{
	if (einfo->type & KERR_FREE_MESSAGE) {
		KFREE(einfo->mess);
		einfo->type &= ~KERR_FREE_MESSAGE;
	}
}

/*
 * Create and throw an exception resulting from an error during VM processing.
 */
void 
throwError(errorInfo* einfo)
{
	throwException(error2Throwable(einfo));
}

/*
 * Throw an exception.
 */
void
throwExternalException(Hjava_lang_Throwable* eobj)
{
	struct _exceptionFrame frame;
	if (eobj == 0) {
		fprintf(stderr, "Exception thrown on null object ... aborting\n");
		ABORT();
		EXIT(1);
	}
	FIRSTFRAME(frame, eobj);
	dispatchException(eobj, &frame);
}

void
throwOutOfMemory(void)
{
	Hjava_lang_Throwable* err;

	err = OutOfMemoryError;
	if (err != NULL) {
		throwException(err);
	}
	fprintf(stderr, "(Insufficient memory)\n");
	EXIT(-1);
}

void*
nextFrame(void* fm)
{  
#if defined(TRANSLATOR)
#if defined(STACK_NEXT_FRAME)
	STACK_NEXT_FRAME((exceptionFrame*)fm);
        if (jthread_on_current_stack((void *)NEXTFRAME((exceptionFrame*)fm))) {
		return (fm);
	}
	else {
		return (0);
	}
#else
        exceptionFrame* nfm;

        nfm = (exceptionFrame*)NEXTFRAME(fm);
        /* Note: this should obsolete the FRAMEOKAY macro */
        if (nfm && jthread_on_current_stack((void *)NEXTFRAME(nfm))) {
                return (nfm);
        }
        else {
                return (0);
        }
#endif
#else
        vmException* nfm;
        nfm = ((vmException*)fm)->prev;
        if (nfm != 0 && nfm->meth != (Method*)1) {
                return (nfm);
        }
        else {
                return (0);
        }
#endif
}

static
void
dispatchException(Hjava_lang_Throwable* eobj, struct _exceptionFrame* baseframe)
{
	const char* cname;
	Hjava_lang_Class* class;
	Hjava_lang_Object* obj;
	iLock* lk;
	Hjava_lang_Thread* ct;

#if defined(INTS_DISABLED)
	/*
	 * We should never try to dispatch an exception while interrupts are 
	 * disabled.  If the threading system provides a means to do so, 
	 * check that we don't attempt to do it anyway.
	 */
	assert(!INTS_DISABLED());
#endif

	ct = getCurrentThread();

	class = OBJECT_CLASS(&eobj->base);
	cname = CLASS_CNAME(class);

	/* Save exception object */
	unhand(ct)->exceptObj = eobj;

	/* Search down exception stack for a match */
#if defined(INTERPRETER)
	{
		exceptionInfo einfo;
		vmException* frame;
		bool res;

		for (frame = (vmException*)unhand(ct)->exceptPtr; frame != 0; frame = frame->prev) {

			if (frame->meth == (Method*)1) {
				unhand(ct)->exceptPtr = (struct Hkaffe_util_Ptr*)frame;
				Kaffe_JNIExceptionHandler();
			}

			/* Look for handler */
			res = findExceptionBlockInMethod(frame->pc, eobj->base.dtable->class, frame->meth, &einfo);

			/* Find the sync. object */
			if (einfo.method == 0 || (einfo.method->accflags & ACC_SYNCHRONISED) == 0) {
				obj = 0;
			}
			else if (einfo.method->accflags & ACC_STATIC) {
				obj = &einfo.class->head;
			}
			else {
				obj = frame->mobj;
			}

			/* If handler found, call it */
			if (res == true) {
				unhand(ct)->needOnStack = STACK_HIGH;
				frame->pc = einfo.handler;
				JTHREAD_LONGJMP(frame->jbuf, 1);
			}


			/* If not here, exit monitor if synchronised. */
			lk = getLock(obj);
			if (lk != 0 && lk->holder == jthread_current()) {
				unlockKnownJavaMutex(lk);
			}
		}
	}
#elif defined(TRANSLATOR)
	{
		exceptionFrame* frame;
		exceptionInfo einfo;

		for (frame = baseframe; frame != 0; frame = nextFrame(frame)) {
			Method *meth;
			
			meth = findExceptionInMethod(PCFRAME(frame), class, &einfo);

                        if (einfo.method == 0 && PCFRAME(frame) >= Kaffe_JNI_estart && PCFRAME(frame) < Kaffe_JNI_eend) {
				Kaffe_JNIExceptionHandler();
                        }

			/* Find the sync. object */
			if (einfo.method == 0 || (einfo.method->accflags & ACC_SYNCHRONISED) == 0) {
				obj = 0;
			}
			else if (einfo.method->accflags & ACC_STATIC) {
				obj = &einfo.class->head;
			}
			else {
#if defined(FRAMEOBJECT)	/* does this arch support a FRAMEOBJECT macro */
				obj = FRAMEOBJECT(frame);
#else
				/* otherwise, do it the hard way... */
				{
					const char *str;
					/* Set up the necessary state for
					 * the SLOT2 macros to work ---
					 * see jit/machine.c
					 * Since we have the translator lock,
					 * we can clobber the max* variables.
					 */
					enterTranslator();
					maxLocal = einfo.method->localsz;
					maxStack = einfo.method->stacksz;
					str = einfo.method->signature->data;
					maxArgs = sizeofSig(&str, false);
					maxTemp = MAXTEMPS - 1;
					/* NB: we assume that the JIT will have
					 * spilled the 'this' object in the
					 * stack location for slot zero.
					 */
					obj = (fpframe(frame))[SLOT2ARGOFFSET(0)/SLOTSIZE];
					leaveTranslator();
				}
#endif
			}

#if defined(HAVE_GCJ_SUPPORT)
			/* If this is a GCJ class - dispatch in that */
			if (einfo.method != 0 && CLASS_GCJ(einfo.method->class)) {
				gcjDispatchException(frame, &einfo, eobj);
			}
#endif

			/* Handler found - dispatch exception */
			if (einfo.handler != 0) {
				unhand(ct)->exceptObj = 0;
				unhand(ct)->needOnStack = STACK_HIGH;
				CALL_KAFFE_EXCEPTION(frame, einfo, eobj);
			}

			/* If method found and synchronised, unlock the lock */
			lk = getLock(obj);
			if (lk != 0 && lk->holder == jthread_current()) {
				unlockKnownJavaMutex(lk);
			}
#if defined(KAFFE_PROFILER)
			/* If method found and profiler enable, fix time */
			if (profFlag && meth) {
				profiler_click_t end;
				profiler_get_clicks(end);
				meth->totalClicks += end;
			}
#endif
		}
	}
#endif

	/* Clear held exception object */
	unhand(ct)->exceptObj = 0;

	/* We must catch 'java.lang.ThreadDeath' exceptions now and
	 * kill the thread rather than the machine.
	 */
	if (strcmp(cname, THREADDEATHCLASS) == 0) {
		exitThread();
	} 

	/* I don't know what to do here. */
	fprintf(stderr, "Internal error.\n"
		"Please check your CLASSPATH and your installation.\n");

	/*
	 * Print this so we get more informative mail...
	 */
	fprintf(stderr, "Exception thrown was of type `%s'\n", cname);
	{
		Hjava_lang_String *msg;
		msg = unhand((Hjava_lang_Throwable*)eobj)->message;
		if (msg) {
			fprintf(stderr, "Message was `%s'\n",
				stringJava2C(msg));
		} else {
			fprintf(stderr, "NULL message\n");
		}
	}
	ABORT();
}

/*
 * Setup the internal exceptions.
 */
void
initExceptions(void)
{
DBG(INIT,	
	dprintf("initExceptions()\n");			
    )
	/* Catch signals we need to convert to exceptions */
	jthread_initexceptions(nullException, floatingException);
}

/*
 * Null exception - catches bad memory accesses.
 */
static void
nullException(struct _exceptionFrame *frame)
{
	Hjava_lang_Throwable* npe;

	npe = (Hjava_lang_Throwable*)NullPointerException;
	unhand(npe)->backtrace = buildStackTrace(frame);
	dispatchException(npe, frame);
}

/*
 * Division by zero.
 */
static void
floatingException(struct _exceptionFrame *frame)
{
	Hjava_lang_Throwable* ae;

	ae = (Hjava_lang_Throwable*)ArithmeticException;
	unhand(ae)->backtrace = buildStackTrace(frame);
	dispatchException(ae, frame);
}

#if defined(TRANSLATOR)
/*
 * Find exception in method.
 */
static Method *
findExceptionInMethod(uintp pc, Hjava_lang_Class* class, exceptionInfo* info)
{
	Method* ptr;

	info->handler = 0;
	info->class = 0;
	info->method = 0;

	ptr = findMethodFromPC(pc);
	if (ptr != 0) {
		if (findExceptionBlockInMethod(pc, class, ptr, info) == true) {
			return ptr;
		}
	}
DBG(ELOOKUP,	dprintf("Exception not found.\n");			)
	return ptr;
}
#endif

/*
 * Look for exception block in method.
 * Returns true if there is an exception handler, false otherwise.
 */
static bool
findExceptionBlockInMethod(uintp pc, Hjava_lang_Class* class, Method* ptr, exceptionInfo* info)
{
	jexceptionEntry* eptr;
	Hjava_lang_Class* cptr;
	int i;

	/* Stash method & class */
	info->method = ptr;
	info->class = ptr->class;

	eptr = &ptr->exception_table->entry[0];

	/* Right method - look for exception */
	if (ptr->exception_table == 0) {
		return (false);
	}
DBG(ELOOKUP,	
	dprintf("Nr of exceptions = %d\n", ptr->exception_table->length); )

	for (i = 0; i < ptr->exception_table->length; i++) {
		uintp start_pc = eptr[i].start_pc;
		uintp end_pc = eptr[i].end_pc;
		uintp handler_pc = eptr[i].handler_pc;

DBG(ELOOKUP,	dprintf("Exceptions %x (%x-%x)\n", pc, start_pc, end_pc); )
		if (pc < start_pc || pc > end_pc) {
			continue;
		}
DBG(ELOOKUP,	dprintf("Found exception 0x%x\n", handler_pc); )

		/* Found exception - is it right type */
		if (eptr[i].catch_idx == 0) {
			info->handler = handler_pc;
			return (true);
		}
		/* Did I try to resolve that catch type before */
		if (eptr[i].catch_type == UNRESOLVABLE_CATCHTYPE) {
			return (false);
		}
		/* Resolve catch class if necessary */
		if (eptr[i].catch_type == NULL) {
			errorInfo info;
			eptr[i].catch_type = getClass(eptr[i].catch_idx, ptr->class, &info);
			/* 
			 * If we could not resolve the catch class, then we
			 * must a) record that fact to guard against possible
			 * recursive attempts to load it and b) throw the error 
			 * resulting from that failure and forget about the 
			 * current exception.
			 */
			if (eptr[i].catch_type == NULL) {
DBG(ELOOKUP|DBG_RESERROR,
				dprintf("Couldn't resolve catch class\n");
    )
				eptr[i].catch_type = UNRESOLVABLE_CATCHTYPE;
				throwError(&info);
				return (false);
			}
		}
                for (cptr = class; cptr != 0; cptr = cptr->superclass) {
                        if (cptr == eptr[i].catch_type) {
                                info->handler = handler_pc;
                                return (true);
                        }
                }
	}
	return (false);
}
