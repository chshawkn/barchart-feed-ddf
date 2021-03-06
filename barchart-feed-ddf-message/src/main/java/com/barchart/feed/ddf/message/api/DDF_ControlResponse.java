/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.message.api;

import com.barchart.feed.base.values.api.TextValue;
import com.barchart.util.common.anno.NotMutable;

/**
 * represents ddf feed server response message.
 */
@NotMutable
public interface DDF_ControlResponse extends DDF_ControlBase {

	/**
	 * 
	 * @return The TextValue comment returned by the server in a response
	 *         message.
	 */
	TextValue getComment();

}
