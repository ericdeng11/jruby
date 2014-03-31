/*
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.call;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import org.jruby.truffle.nodes.InlinableMethodImplementation;
import org.jruby.truffle.runtime.*;
import org.jruby.truffle.runtime.core.*;
import org.jruby.truffle.runtime.lookup.*;
import org.jruby.truffle.runtime.methods.*;

import java.util.Arrays;

/**
 * A node in the dispatch chain that comes after the boxing point and caches a method on a full
 * boxed Ruby BasicObject, matching it by looking at the lookup node and assuming it has not been
 * modified.
 */
public class CachedBoxedDispatchNode extends BoxedDispatchNode {

    private final LookupNode expectedLookupNode;
    private final Assumption unmodifiedAssumption;
    private final RubyMethod method;
    @Child protected CallNode callNode;

    @Child protected BoxedDispatchNode next;

    public CachedBoxedDispatchNode(RubyContext context, SourceSection sourceSection, LookupNode expectedLookupNode, RubyMethod method, BoxedDispatchNode next) {
        super(context, sourceSection);

        assert expectedLookupNode != null;
        assert method != null;

        this.expectedLookupNode = expectedLookupNode;
        unmodifiedAssumption = expectedLookupNode.getUnmodifiedAssumption();
        this.method = method;
        this.next = next;
        this.callNode = Truffle.getRuntime().createCallNode(method.getImplementation().getCallTarget());
    }

    @Override
    public Object dispatch(VirtualFrame frame, RubyBasicObject receiverObject, RubyProc blockObject, Object[] argumentsObjects) {
        // Check the lookup node is what we expect

        if (receiverObject.getLookupNode() != expectedLookupNode) {
            return next.dispatch(frame, receiverObject, blockObject, argumentsObjects);
        }

        // Check the class has not been modified

        try {
            unmodifiedAssumption.check();
        } catch (InvalidAssumptionException e) {
            return respecialize("class modified", frame, receiverObject, blockObject, argumentsObjects);
        }

        // Call the method

        final Object[] modifiedArgumentsObjects;

        CompilerAsserts.compilationConstant(method.getImplementation() instanceof InlinableMethodImplementation && ((InlinableMethodImplementation) method.getImplementation()).getShouldAppendCallNode());

        if (method.getImplementation() instanceof InlinableMethodImplementation && ((InlinableMethodImplementation) method.getImplementation()).getShouldAppendCallNode()) {
            modifiedArgumentsObjects = Arrays.copyOf(argumentsObjects, argumentsObjects.length + 1);
            modifiedArgumentsObjects[modifiedArgumentsObjects.length - 1] = this;
        } else {
            modifiedArgumentsObjects = argumentsObjects;
        }

        RubyArguments args = new RubyArguments(frame.materialize(), receiverObject, blockObject, modifiedArgumentsObjects);
        return callNode.call(frame.pack(), args);
    }

}
