package com.jrasp.core.enhance.weaver.asm;

import org.objectweb.asm.Type;

import java.com.jrasp.spy.Spy;

public interface AsmTypes {
    Type ASM_TYPE_SPY = Type.getType(Spy.class);
    Type ASM_TYPE_OBJECT = Type.getType(Object.class);
    Type ASM_TYPE_INT = Type.getType(int.class);
    Type ASM_TYPE_SPY_RET = Type.getType(Spy.Ret.class);
    Type ASM_TYPE_THROWABLE = Type.getType(Throwable.class);
    Type ASM_TYPE_CLASS = Type.getType(Class.class);
}
