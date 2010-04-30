package org.atmosphere.samples.lpchat

import scala.reflect.BeanProperty;
import java.util.Date;

class Message(@BeanProperty val date : Date,
              @BeanProperty val message : String) {
}
