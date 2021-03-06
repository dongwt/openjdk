/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.jshell.execution;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.SPIResolutionException;

/**
 * An {@link ExecutionControl} implementation that runs in the current process.
 * May be used directly, or over a channel with
 * {@link Util#forwardExecutionControl(ExecutionControl, java.io.ObjectInput, java.io.ObjectOutput) }.
 *
 * @author Robert Field
 * @author Jan Lahoda
 */
public class DirectExecutionControl implements ExecutionControl {

    private final LoaderDelegate loaderDelegate;

    /**
     * Creates an instance, delegating loader operations to the specified
     * delegate.
     *
     * @param loaderDelegate the delegate to handle loading classes
     */
    public DirectExecutionControl(LoaderDelegate loaderDelegate) {
        this.loaderDelegate = loaderDelegate;
    }

    /**
     * Create an instance using the default class loading.
     */
    public DirectExecutionControl() {
        this(new DefaultLoaderDelegate());
    }

    @Override
    public void load(ClassBytecodes[] cbcs)
            throws ClassInstallException, NotImplementedException, EngineTerminationException {
        loaderDelegate.load(cbcs);
    }

    @Override
    public void redefine(ClassBytecodes[] cbcs)
            throws ClassInstallException, NotImplementedException, EngineTerminationException {
        throw new NotImplementedException("redefine not supported");
    }

    @Override
    public String invoke(String className, String methodName)
            throws RunException, InternalException, EngineTerminationException {
        Method doitMethod;
        try {
            Class<?> klass = findClass(className);
            doitMethod = klass.getDeclaredMethod(methodName, new Class<?>[0]);
            doitMethod.setAccessible(true);
        } catch (Throwable ex) {
            throw new InternalException(ex.toString());
        }

        try {
            clientCodeEnter();
            String result = invoke(doitMethod);
            System.out.flush();
            return result;
        } catch (RunException | InternalException | EngineTerminationException ex) {
            throw ex;
        } catch (SPIResolutionException ex) {
            return throwConvertedInvocationException(ex);
        } catch (InvocationTargetException ex) {
            return throwConvertedInvocationException(ex.getCause());
        } catch (Throwable ex) {
            return throwConvertedOtherException(ex);
        } finally {
            clientCodeLeave();
        }
    }

    @Override
    public String varValue(String className, String varName)
            throws RunException, EngineTerminationException, InternalException {
        Object val;
        try {
            Class<?> klass = findClass(className);
            Field var = klass.getDeclaredField(varName);
            var.setAccessible(true);
            val = var.get(null);
        } catch (Throwable ex) {
            throw new InternalException(ex.toString());
        }

        try {
            clientCodeEnter();
            return valueString(val);
        } catch (Throwable ex) {
            return throwConvertedInvocationException(ex);
        } finally {
            clientCodeLeave();
        }
    }

    @Override
    public void addToClasspath(String cp)
            throws EngineTerminationException, InternalException {
        loaderDelegate.addToClasspath(cp);
    }

    @Override
    public void setClasspath(String path)
            throws EngineTerminationException, InternalException {
        loaderDelegate.setClasspath(path);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Not supported.
     */
    @Override
    public void stop()
            throws EngineTerminationException, InternalException {
        throw new NotImplementedException("stop: Not supported.");
    }

    @Override
    public Object extensionCommand(String command, Object arg)
            throws RunException, EngineTerminationException, InternalException {
        throw new NotImplementedException("Unknown command: " + command);
    }

    @Override
    public void close() {
    }

    /**
     * Finds the class with the specified binary name.
     *
     * @param name the binary name of the class
     * @return the Class Object
     * @throws ClassNotFoundException if the class could not be found
     */
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return loaderDelegate.findClass(name);
    }

    /**
     * Invoke the specified "doit-method", a static method with no parameters.
     * The {@link DirectExecutionControl#invoke(java.lang.String, java.lang.String) }
     * in this class will call this to invoke.
     *
     * @param doitMethod the Method to invoke
     * @return the value or null
     * @throws Exception any exceptions thrown by
     * {@link java.lang.reflect.Method#invoke(Object, Object...) }
     * or any {@link ExecutionControl.ExecutionControlException}
     * to pass-through.
     */
    protected String invoke(Method doitMethod) throws Exception {
        Object res = doitMethod.invoke(null, new Object[0]);
        return valueString(res);
    }

    /**
     * Converts the {@code Object} value from
     * {@link ExecutionControl#invoke(String, String)  } or
     * {@link ExecutionControl#varValue(String, String)   } to {@code String}.
     *
     * @param value the value to convert
     * @return the {@code String} representation
     */
    protected static String valueString(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            return "\"" + (String) value + "\"";
        } else if (value instanceof Character) {
            return "'" + value + "'";
        } else {
            return value.toString();
        }
    }

    /**
     * Converts incoming exceptions in user code into instances of subtypes of
     * {@link ExecutionControl.ExecutionControlException} and throws the
     * converted exception.
     *
     * @param cause the exception to convert
     * @return never returns as it always throws
     * @throws ExecutionControl.RunException for normal exception occurrences
     * @throws ExecutionControl.InternalException for internal problems
     */
    protected String throwConvertedInvocationException(Throwable cause) throws RunException, InternalException {
        if (cause instanceof SPIResolutionException) {
            SPIResolutionException spire = (SPIResolutionException) cause;
            throw new ResolutionException(spire.id(), spire.getStackTrace());
        } else {
            throw new UserException(cause.getMessage(), cause.getClass().getName(), cause.getStackTrace());
        }
    }

    /**
     * Converts incoming exceptions in agent code into instances of subtypes of
     * {@link ExecutionControl.ExecutionControlException} and throws the
     * converted exception.
     *
     * @param ex the exception to convert
     * @return never returns as it always throws
     * @throws ExecutionControl.RunException for normal exception occurrences
     * @throws ExecutionControl.InternalException for internal problems
     */
    protected String throwConvertedOtherException(Throwable ex) throws RunException, InternalException {
        throw new InternalException(ex.toString());
    }

    /**
     * Marks entry into user code.
     *
     * @throws ExecutionControl.InternalException in unexpected failure cases
     */
    protected void clientCodeEnter() throws InternalException {
    }

    /**
     * Marks departure from user code.
     *
     * @throws ExecutionControl.InternalException in unexpected failure cases
     */
    protected void clientCodeLeave() throws InternalException {
    }

}
