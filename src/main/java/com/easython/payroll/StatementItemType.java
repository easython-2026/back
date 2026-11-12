package com.easython.payroll;

public enum StatementItemType {
	BASE_PAY(false), OVERTIME_PAY(false), NIGHT_PAY(false), HOLIDAY_PAY(false), WEEKLY_HOLIDAY_PAY(false), ALLOWANCE(false),
	MEAL_DEDUCTION(true), LODGING_DEDUCTION(true), TAX_INSURANCE(true), OTHER_DEDUCTION(true);

	private final boolean deduction;

	StatementItemType(boolean deduction) {
		this.deduction = deduction;
	}

	public boolean isDeduction() {
		return deduction;
	}
}
