/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.historical.enums;

import com.barchart.feed.base.enums.EnumCodeString;

// TODO: Auto-generated Javadoc
/**
 * The Enum DDF_QueryEodVolume.
 */
public enum DDF_QueryEodVolume implements EnumCodeString {

	/** default */
	CONTRACT("contract"), //

	TOTAL("total"), //

	SUMTOTAL("sumtotal"), //

	SUMCONTRACT("sumcontract"), //

	SUM("sum"), //
	/** The code. */
	;

	public final String code;

	/**
	 * used in page url and as xml code.
	 * 
	 * @return the string
	 */
	@Override
	public final String code() {
		return code;
	}

	private DDF_QueryEodVolume(final String code) {
		this.code = code;
	}

	private static final DDF_QueryEodVolume[] ENUM_VALUES = values();

	/**
	 * Values unsafe.
	 * 
	 * @return the dD f_ query eod volume[]
	 */
	@Deprecated
	public static final DDF_QueryEodVolume[] valuesUnsafe() {
		return ENUM_VALUES;
	}

	/**
	 * From code.
	 * 
	 * @param code
	 *            the code
	 * @return the dD f_ query eod volume
	 */
	public static final DDF_QueryEodVolume fromCode(final String code) {
		for (final DDF_QueryEodVolume known : ENUM_VALUES) {
			if (known.code.equalsIgnoreCase(code)) {
				return known;
			}
		}
		return CONTRACT;
	}

}
