package net.querz.mcaselector.headless;

import net.querz.mcaselector.filter.Comparator;
import net.querz.mcaselector.filter.Filter;
import net.querz.mcaselector.filter.FilterType;
import net.querz.mcaselector.filter.GroupFilter;
import net.querz.mcaselector.filter.Operator;

public class FilterParser {

	private StringPointer ptr;

	public FilterParser(String filter) {
		ptr = new StringPointer(filter);
	}

	public GroupFilter parse() throws ParseException {
		GroupFilter group = new GroupFilter();
		ptr.skipWhitespace();
		boolean first = true;
		while (ptr.hasNext() && ptr.currentChar() != ')') {
			// read operator
			Operator operator;
			if (first) {
				operator = Operator.AND;
				first = false;
			} else {
				operator = parseOperator();
			}

			ptr.skipWhitespace();

			// parse group
			if (ptr.currentChar() == '(') {
				ptr.next();
				GroupFilter child = parse();
				child.setOperator(operator);
				group.addFilter(child);
				ptr.skipWhitespace();
				ptr.expectChar(')');
				ptr.skipWhitespace();
				continue;
			}

			group.addFilter(parseFilterType(operator));

			ptr.skipWhitespace();
		}
		ptr.skipWhitespace();
		return group;
	}

	private Filter<?> parseFilterType(Operator operator) throws ParseException {
		// parse value
		String type = ptr.parseSimpleString();
		FilterType t = FilterType.getByName(type);

		if (t == null) {
			throw ptr.parseException("invalid filter type");
		}

		Comparator comparator = parseComparator();

		Filter<?> f = t.create();
		if (f == null) {
			throw ptr.parseException("unable to create filter for type " + type);
		}
		Comparator allowed = null;
		for (Comparator c : f.getComparators()) {
			if (c == comparator) {
				allowed = c;
				break;
			}
		}
		if (allowed == null) {
			throw ptr.parseException("comparator " + comparator + " not allowed for filter type " + type);
		}
		f.setOperator(operator);
		f.setComparator(allowed);
		return parseFilterValue(f);
	}

	private Filter<?> parseFilterValue(Filter<?> filter) throws ParseException {
		ptr.skipWhitespace();
		if (ptr.currentChar() == '"') {
			filter.setFilterValue(ptr.parseQuotedString());
		} else {
			filter.setFilterValue(ptr.parseSimpleString(this::isValidCharacter));
		}
		if (!filter.isValid()) {
			throw ptr.parseException("invalid value");
		}
		return filter;
	}

	private Comparator parseComparator() throws ParseException {
		ptr.skipWhitespace();
		Comparator comparator = Comparator.fromQuery(ptr.parseSimpleString());
		if (comparator == null) {
			throw ptr.parseException("invalid comparator");
		}
		return comparator;
	}

	private Operator parseOperator() throws ParseException {
		String op = ptr.parseSimpleString();
		Operator operator = Operator.getByName(op);
		if (operator == null) {
			throw ptr.parseException("invalid operator " + op);
		}
		return operator;
	}

	private boolean isValidCharacter(char c) {
		return c >= 'a' && c <= 'z'
				|| c >= 'A' && c <= 'Z'
				|| c >= '0' && c <= '9'
				|| c == ','
				|| c == '-'
				|| c == '+'
				|| c == ':';
	}
}
