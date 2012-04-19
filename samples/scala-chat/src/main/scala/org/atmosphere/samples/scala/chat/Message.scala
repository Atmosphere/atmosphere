/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.samples.scala.chat

import scala.reflect.BeanInfo
import scala.reflect.BeanProperty
import javax.xml.bind.annotation.XmlRootElement

@BeanInfo
@XmlRootElement
class Message {

  @BeanProperty
  var author: String = ""
  @BeanProperty
  var message: String = ""

  def this(author: String, message: String) {
    this ()
    this.author = author
    this.message = message
  }

}