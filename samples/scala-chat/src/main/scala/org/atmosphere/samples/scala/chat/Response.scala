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

import java.util.Date
import javax.xml.bind.annotation.XmlRootElement
import reflect.{BeanProperty, BeanInfo}

@BeanInfo
@XmlRootElement
class Response {
  @BeanProperty
  var text: String = null
  @BeanProperty
  var author: String = null
  @BeanProperty
  var time: Long = 0L

  def this(author: String, text: String) {
    this ()
    this.author = author
    this.text = text
    this.time = new Date().getTime
  }

}