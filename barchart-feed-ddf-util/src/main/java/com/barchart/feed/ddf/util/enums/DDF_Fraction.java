/**
 * Copyright (C) 2011-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.feed.ddf.util.enums;

import static com.barchart.util.common.ascii.ASCII.QUEST;
import static com.barchart.util.common.ascii.ASCII.STAR;
import static com.barchart.util.common.ascii.ASCII._0_;
import static com.barchart.util.common.ascii.ASCII._1_;
import static com.barchart.util.common.ascii.ASCII._2_;
import static com.barchart.util.common.ascii.ASCII._3_;
import static com.barchart.util.common.ascii.ASCII._4_;
import static com.barchart.util.common.ascii.ASCII._5_;
import static com.barchart.util.common.ascii.ASCII._6_;
import static com.barchart.util.common.ascii.ASCII._7_;
import static com.barchart.util.common.ascii.ASCII._8_;
import static com.barchart.util.common.ascii.ASCII._9_;
import static com.barchart.util.common.ascii.ASCII._A_;
import static com.barchart.util.common.ascii.ASCII._B_;
import static com.barchart.util.common.ascii.ASCII._C_;
import static com.barchart.util.common.ascii.ASCII._D_;
import static com.barchart.util.common.ascii.ASCII._E_;
import static com.barchart.util.common.ascii.ASCII._F_;
import static com.barchart.util.common.ascii.ASCII._G_;
import static com.barchart.util.common.ascii.ASCII._H_;
import static java.lang.Math.pow;

import com.barchart.feed.base.enums.EnumByteOrdinal;
import com.barchart.feed.base.enums.EnumCodeByte;
import com.barchart.feed.base.values.api.Fraction;
import com.barchart.feed.base.values.provider.ValueBuilder;
import com.barchart.util.common.math.MathExtra;

// TODO: Auto-generated Javadoc
/** a.k.a base code */
public enum DDF_Fraction implements EnumCodeByte, EnumByteOrdinal {

	// binary range; exponent is power of 2;
	Q2(_0_, -10, ValueBuilder.newFraction(2, -1)), // 1/2 XXX: unit code invalid
	Q4(_1_, -11, ValueBuilder.newFraction(2, -2)), // 1/4 XXX: unit code invalid
	Q8(_2_, -1, ValueBuilder.newFraction(2, -3)), // 1/8
	Q16(_3_, -2, ValueBuilder.newFraction(2, -4)), // 1/16
	Q32(_4_, -3, ValueBuilder.newFraction(2, -5)), // 1/32
	Q64(_5_, -4, ValueBuilder.newFraction(2, -6)), // 1/64
	Q128(_6_, -5, ValueBuilder.newFraction(2, -7)), // 1/128
	Q256(_7_, -6, ValueBuilder.newFraction(2, -8)), // 1/256

	// decimal range; exponent is power of 10;
	Z0(_8_, 0, ValueBuilder.newFraction(10, 0)), // 1
	N1(_9_, 1, ValueBuilder.newFraction(10, -1)), // 10
	N2(_A_, 2, ValueBuilder.newFraction(10, -2)), // 100
	N3(_B_, 3, ValueBuilder.newFraction(10, -3)), // 1K
	N4(_C_, 4, ValueBuilder.newFraction(10, -4)), // 10K
	N5(_D_, 5, ValueBuilder.newFraction(10, -5)), // 100K
	N6(_E_, 6, ValueBuilder.newFraction(10, -6)), // 1M
	N7(_F_, 7, ValueBuilder.newFraction(10, -7)), // 10M
	N8(_G_, 8, ValueBuilder.newFraction(10, -8)), // 100M
	N9(_H_, 9, ValueBuilder.newFraction(10, -9)), // 1B

	ZZ(STAR, 0, ValueBuilder.newFraction(10, 0)), // 1

	UNKNOWN(QUEST, 0, ValueBuilder.newFraction(10, 0)), //

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.barchart.util.enums.EnumByteOrdinal#ord()
	 */
	;

	@Override
	public final byte ord() {
		return ord;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.barchart.util.enums.EnumCodeByte#code()
	 */
	@Override
	public final byte code() {
		return baseCode;
	}

	/** form #1 of ddf price exponent encoding. */
	public final byte baseCode;

	/** form #2 of ddf price exponent encoding. */
	public final int unitCode;

	/** byte sized ordinal of this enum. */
	public final byte ord;

	/** base-10 exponent and denominator, regardless of enum base. */
	public final int decimalExponent;

	/** The decimal denominator. */
	public final long decimalDenominator;

	/** base-2 or base-10 exponent and denominator, depending on enum base. */
	public final int nativeExponent;

	/** The native denominator. */
	public final long nativeDenominator;

	/** number of digits needed to display fraction in native form. */
	public final int spaces;

	/** "positive" difference between native and decimal spaces. */
	public final long spacesDenomPlus;

	/** "negative" difference between native and decimal spaces. */
	public final long spacesDenomMinus;

	/** base generic fraction api enum used by ddf fraction enum. */
	public final Fraction fraction;

	/** convenience flag to differentiate base-2 vs base-10 fractions. */
	public final boolean isBinary;

	private DDF_Fraction(final byte baseCode, final int unitCode,
			final Fraction fraction) {

		this.baseCode = baseCode;
		this.unitCode = unitCode;

		this.ord = (byte) ordinal();

		this.fraction = fraction;
		this.decimalExponent = fraction.decimalExponent();
		this.decimalDenominator = fraction.decimalDenominator();

		if(fraction.base() == 2) {
			this.isBinary = true;
		} else {
			this.isBinary = false;
		}

		this.nativeExponent = fraction.exponent();
		this.nativeDenominator = fraction.denominator();

		this.spaces = fraction.places();

		this.spacesDenomPlus = (long) pow(10, spaces);
		this.spacesDenomMinus = (long) pow(10, -decimalExponent - spaces);

	}

	private final static DDF_Fraction[] ENUM_VALUES = values();

	/**
	 * Values unsafe.
	 * 
	 * @return the dD f_ fraction[]
	 */
	@Deprecated
	public final static DDF_Fraction[] valuesUnsafe() {
		return ENUM_VALUES;
	}

	static {
		// validate use of byte ord
		MathExtra.castIntToByte(ENUM_VALUES.length);
	}

	// TODO optimize: replace with 2 tableswitch blocks;
	// http://java.sun.com/docs/books/jvms/second_edition/html/Compiling.doc.html#14942
	/**
	 * From base code.
	 * 
	 * @param baseCode
	 *            the base code
	 * @return the dD f_ fraction
	 */
	public final static DDF_Fraction fromBaseCode(final byte baseCode) {
		for (final DDF_Fraction known : ENUM_VALUES) {
			if (known.baseCode == baseCode) {
				return known;
			}
		}
		return UNKNOWN;
	}

	// TODO optimize: replace with 2 tableswitch blocks;
	// http://java.sun.com/docs/books/jvms/second_edition/html/Compiling.doc.html#14942
	/**
	 * From unit code.
	 * 
	 * @param unitCode
	 *            the unit code
	 * @return the dD f_ fraction
	 */
	public final static DDF_Fraction fromUnitCode(final int unitCode) {
		for (final DDF_Fraction known : ENUM_VALUES) {
			if (known.unitCode == unitCode) {
				return known;
			}
		}
		return UNKNOWN;
	}

	// TODO optimize; replace with 1 tableswitch block
	/**
	 * From fraction.
	 * 
	 * @param fraction
	 *            the fraction
	 * @return the dD f_ fraction
	 */
	public final static DDF_Fraction fromFraction(final Fraction fraction) {
		for (final DDF_Fraction known : ENUM_VALUES) {
			if (known.fraction == fraction) {
				return known;
			}
		}
		return UNKNOWN;
	}

	/**
	 * From ord.
	 * 
	 * @param ord
	 *            the ord
	 * @return the dD f_ fraction
	 */
	public final static DDF_Fraction fromOrd(final byte ord) {
		return ENUM_VALUES[ord];
	}

	/**
	 * Checks if is known.
	 * 
	 * @return true, if is known
	 */
	public final boolean isKnown() {
		return this != UNKNOWN;
	}

}
