/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
/*
 * Copyright 2009 Richard Zschech.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.gwt.rebind;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.List;

import org.atmosphere.gwt.client.SerialMode;
import org.atmosphere.gwt.client.SerialTypes;

import com.google.gwt.core.ext.GeneratorExt;
import com.google.gwt.core.ext.GeneratorContextExt;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JField;
import com.google.gwt.core.ext.typeinfo.JType;
import com.google.gwt.core.ext.typeinfo.NotFoundException;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.dev.javac.rebind.RebindResult;
import com.google.gwt.dev.javac.rebind.RebindStatus;
import com.google.gwt.dev.util.collect.Lists;
import com.google.gwt.rpc.linker.RpcDataArtifact;
import com.google.gwt.user.client.rpc.impl.Serializer;
import com.google.gwt.user.rebind.ClassSourceFileComposerFactory;
import com.google.gwt.user.rebind.SourceWriter;
import com.google.gwt.user.rebind.rpc.SerializableTypeOracle;
import com.google.gwt.user.rebind.rpc.SerializableTypeOracleBuilder;
import com.google.gwt.user.rebind.rpc.SerializationUtils;
import com.google.gwt.user.rebind.rpc.TypeSerializerCreator;

public class SerializerGenerator extends GeneratorExt {
	
	@Override
	public RebindResult generateIncrementally(TreeLogger logger, GeneratorContextExt context, String typeName) throws UnableToCompleteException {
		
		TypeOracle typeOracle = context.getTypeOracle();
		
		// Create the CometSerializer impl
		String packageName = "comet";
		String className = typeName.replace('.', '_') + "Impl";
		PrintWriter printWriter = context.tryCreate(logger, packageName, className);
		
		if (printWriter != null) {
			
			try {
				JClassType type = typeOracle.getType(typeName);
				SerialTypes annotation = type.getAnnotation(SerialTypes.class);
				if (annotation == null) {
					logger.log(TreeLogger.ERROR, "No SerialTypes annotation on CometSerializer type: " + typeName);
					throw new UnableToCompleteException();
				}
				
				SerializableTypeOracleBuilder typesSentToBrowserBuilder = new SerializableTypeOracleBuilder(logger, context.getPropertyOracle(), typeOracle);
				SerializableTypeOracleBuilder typesSentFromBrowserBuilder = new SerializableTypeOracleBuilder(logger, context.getPropertyOracle(), typeOracle);
				
				for (Class<? extends Serializable> serializable : annotation.value()) {
					int rank = 0;
					if (serializable.isArray()) {
						while(serializable.isArray()) {
							serializable = (Class<? extends Serializable>) serializable.getComponentType();
							rank++;
						}
					}
						
					JType resolvedType = typeOracle.getType(serializable.getCanonicalName());
					while (rank > 0) {
						resolvedType = typeOracle.getArrayType(resolvedType);
						rank--;
					}
					
					typesSentToBrowserBuilder.addRootType(logger, resolvedType);
                    typesSentFromBrowserBuilder.addRootType(logger, resolvedType);
				}
				
				// Create a resource file to receive all of the serialization information
				// computed by STOB and mark it as private so it does not end up in the
				// output.
				OutputStream pathInfo = context.tryCreateResource(logger, typeName + ".rpc.log");
				PrintWriter writer = new PrintWriter(new OutputStreamWriter(pathInfo));
				writer.write("====================================\n");
				writer.write("Types potentially sent from server:\n");
				writer.write("====================================\n\n");
				writer.flush();
				
				typesSentToBrowserBuilder.setLogOutputWriter(writer);
				SerializableTypeOracle typesSentToBrowser = typesSentToBrowserBuilder.build(logger);

				writer.write("===================================\n");
				writer.write("Types potentially sent from browser:\n");
				writer.write("===================================\n\n");
				writer.flush();
				typesSentFromBrowserBuilder.setLogOutputWriter(writer);
			    SerializableTypeOracle typesSentFromBrowser = typesSentFromBrowserBuilder.build(logger);
				
				writer.close();
				
				if (pathInfo != null) {
					context.commitResource(logger, pathInfo).setPrivate(true);
				}
				
				// Create the serializer
                final String modifiedTypeName = typeName.replace('.', '_');
                TypeSerializerCreator tsc = new TypeSerializerCreator(logger, typesSentFromBrowser, typesSentToBrowser, context, "comet." + modifiedTypeName, modifiedTypeName);
				String realize = tsc.realize(logger);
				
				// Create the CometSerializer impl
				ClassSourceFileComposerFactory composerFactory = new ClassSourceFileComposerFactory(packageName, className);
				
				composerFactory.addImport(Serializer.class.getName());
				composerFactory.addImport(SerialMode.class.getName());
				
				composerFactory.setSuperclass(typeName);
				// TODO is the SERIALIZER required for DE RPC?
				SourceWriter sourceWriter = composerFactory.createSourceWriter(context, printWriter);
				sourceWriter.print("private Serializer SERIALIZER = new " + realize + "();");
				sourceWriter.print("protected Serializer getSerializer() {return SERIALIZER;}");
				sourceWriter.print("public SerialMode getMode() {return SerialMode." + annotation.mode().name() + ";}");
                sourceWriter.print("public SerialMode getPushMode() {return SerialMode." + annotation.pushmode().name() + ";}");
				sourceWriter.commit(logger);
				
				if (annotation.mode() == SerialMode.DE_RPC) {
					RpcDataArtifact data = new RpcDataArtifact(type.getQualifiedSourceName());
					for (JType t : typesSentToBrowser.getSerializableTypes()) {
						if (!(t instanceof JClassType)) {
							continue;
						}
						JField[] serializableFields = SerializationUtils.getSerializableFields(context.getTypeOracle(), (JClassType) t);
						
						List<String> names = Lists.create();
						for (int i = 0, j = serializableFields.length; i < j; i++) {
							names = Lists.add(names, serializableFields[i].getName());
						}
						
						data.setFields(SerializationUtils.getRpcTypeName(t), names);
					}
					
					context.commitArtifact(logger, data);
				}
			}
			catch (NotFoundException e) {
				logger.log(TreeLogger.ERROR, "", e);
				throw new UnableToCompleteException();
			}
		}
		
		return new RebindResult(RebindStatus.USE_PARTIAL_CACHED, packageName + '.' + className);
	}
}
