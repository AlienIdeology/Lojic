package lojic.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * @author AlienIdeology
 *
 * A lexer of logical expressions
 * Tokenizes a string in terms of {@code alphabetic-chars|parenthesized-formulas|connective-char|unrecognized-char}
 * {@code alphabetic-chars} and {@code parenthesized-formulas} are grouped into char sequences
 *
 * This class is for the Lojic library's internal use only, users should ignore this
 */
class LojicLexer {

    private final LojicParser parser;
    private final String baseString;
    private String string;
    private int index;

    LojicLexer(LojicParser parser, String baseString, String string) {
        this.parser = parser;
        this.baseString = baseString;
        this.string = string;
        this.index = -1;
    }

    /**
     * Set the index of this lexer
     * This method is for the Lojic library's internal use only, users should ignore this
     *
     * @param index The index
     */
    public void setIndex(int index) {
        this.index = index;
    }

    /**
     * Update this lexer's string
     * This method is for the Lojic library's internal use only, users should ignore this
     *
     * @param string The string
     */
    public void updateString(String string) {
        this.string = string;
    }

    @Override
    public String toString() {
        return string;
    }

    /**
     * This method returns a list of Tokens to be used for parsing.
     * This is recursively called in the parser for each formula or parenthesized atoms.
     * lex("P∧Q") => ["P", "∧", "Q"]
     * lex("((P∧Q))") => ["P", "∧", "Q"]
     * lex("(P∧Q)->R") => ["P∧Q", "->", "R"]
     *
     * This method is for the Lojic library's internal use only, users should ignore this
     * @see LojicParser for parsing logical expressions
     *
     * @param location The location of the formula in the base formula in which this lexer starts
     * @return a list of tokens
     */
    public List<Token> lex(int location) {
        List<Token> tokens = new ArrayList<>();

        // create new integer since (int location) is only passed by value
        // when the location is updated at the bottom of the while(true) loop, we need to store it
        int loc = location;

        while (true) {
            while (hasNext()) {
                Token token = handleNext(loc+index+1);
                tokens.add(token); // reader index starts at -1, so offset it by 1
                //System.out.println(tok + "\n" + LojicUtil.generateIndicator(baseString, tok.getLocation()));
            }

            /*
             Deal with unnecessary parenthesis enclosing this atom or formula
             This only strips away ALL parenthesis if the formula is not compound
             A formula is compound when its sub-formula(s) or sub-atom(s) is parenthesized twice of more
             Example: "((A))->((B))"
                      Non Compound "(A)->(B)"
             parse() will later deal with sub-formula(s) or sub-atom(s) that is still parenthesized
             */

            // "((A))" => "(A)" => "A"
            // "(((A&B)))" => "A" "&" "B"
            // "((A)->(B)->(C&D))" => "A" "->" "B" -> "C&D"
            // "((A&B)->(B&C))" => "A&B"
            Token first = tokens.get(0);
            if (tokens.size()==1 && !(first instanceof Token.ParsedFormula)) {
                updateString(first.toString()); // This overrides the old string with the new, un-parenthesized-once string
                loc = loc+1; // Offset by +1 to account for the removal of open parenthesis at the start of the old string
                // "A" => "A"
                // "(A)" => "A"
                if (first.isType(TokenType.ATOM)) {
                    first.setLocation(loc);
                    break;
                // "(((A&B)))" => ""A&B"
                } else {
                    tokens.clear();
                    setIndex(-1);
                }
            }
            else break;
        }

        handleUnary(tokens);
        return tokens;
    }

    /**
     * This method handles the unary connectives in a {@code ParseList<Token>}
     * by grouping it with the adequate token(s) on its right into a ParsedFormula
     *
     * The tokens that will be absorbed into the ParsedFormula include
     * 1. The original unary connective (#1)
     * 2. The unary connective(s) immediately to the right of the original unary connective (#0~n)
     * 3. The atom or formula to the left of the unary connective(s) (#1)
     *
     * Then the method updates the {@code ParseList<Token>} by deleting all the tokens absorbed and
     * replacing them with a single {@link Token.ParsedFormula} object.
     *
     * @param tokens The list of tokens
     */
    private void handleUnary(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if(token.isType(TokenType.UNARY_CONNECTIVE)) {
                Token.ParsedFormula formula = new Token.ParsedFormula(token);
                StringBuilder tokStr = new StringBuilder(token.toString());
                Token right = tokens.get(i+1);
                int removed = 0; // count the times we add to ParsedFormula

                // While the token to a unary connective token's right is also unary, add the token
                while (right.isType(TokenType.UNARY_CONNECTIVE)) {
                    formula.add(right);
                    tokStr.append(right.toString());

                    removed++;
                    right = tokens.get(i+removed+1);
                }

                // When the remaining token is an atom or a formula, add the token
                if (right.isType(TokenType.ATOM)) {
                    formula.add(right);
                    tokStr.append(right.toString());
                    removed++;
                }  else if (right.isType(TokenType.FORMULA)) {
                    formula.add(right);
                    tokStr.append(TokenType.PARENTHESIS_OPEN.OFFICIAL_SYMBOL).append(right.toString()).append(TokenType.PARENTHESIS_CLOSE.OFFICIAL_SYMBOL);
                    // FEATURE: No symbols stripping - No fix yet??
                    removed++;
                }

                // Update the list
                formula.setString(tokStr.toString());
                tokens.set(i, formula);
                for (int j = 0; j < removed; j++) {
                    tokens.remove(i+1);
                }
            }
        }
    }

    /**
     * This method group parenthesized strings into formulas or atoms, and un-parenthesize it.
     * This method also handle all syntax errors.
     *
     * @param location The location of the formula in the base formula in which this lexer starts
     *                 "hello" -> lexer starts at reading "e" -> location = 1
     * @return The next token, could be {@link TokenType}
     *     ATOM,
     *     UNARY_CONNECTIVE,
     *     BINARY_CONNECTIVE,
     *     FORMULA,
     *     END
     * @throws SyntaxException for various syntax errors
     */
    private Token handleNext(int location) {
        Token next = next();
        TokenType type = next.getType();

        // loc: index location in the broader context
        // errorNext and errorNoNext requires loc+1, since we want the indicator to show at the next char
        int loc = location + next.length()-1;

        switch (type) {
            case PARENTHESIS_OPEN: {
                int count = 1;
                StringBuilder cache = new StringBuilder(next.toString());
                errorNext(loc+1, TokenType.BINARY_CONNECTIVE);

                while (count != 0) {
                    String nxt = nextChar();
                    // Cases like "(" or "((" or "((P->Q)"
                    if (nxt == null) { // !hasNext()
                        // "(", "(("
                        if(LojicParser.isOpenParenthesis(cache.substring(cache.length()-1))) {
                            errorNoNext(loc+1);
                        // "((P->Q)"
                        } else {
                            throw new SyntaxException(loc+1, "Missing closing parenthesis",
                                    baseString);
                        }
                    }

                    if (LojicParser.isOpenParenthesis(nxt)) {
                        count++;
                    } else if (LojicParser.isCloseParenthesis(nxt)) {
                        count--;
                    }
                    cache.append(nxt);
                    loc++;
                }

                // After ")"
                errorNext(loc+1, TokenType.UNARY_CONNECTIVE, TokenType.PARENTHESIS_OPEN, TokenType.ATOM);

                String result = cache.toString();
                // Remove parenthesis in front and at the end
                result = result.substring(1, result.length()-1);

                loc -= (cache.length() -2);

                // Empty Formula ()
                if(result.isEmpty()) throw new SyntaxException(loc-1, "Empty formula or atom within parenthesis", baseString);

                // Return formula of atom
                return LojicParser.isAtomic(result) ? new Token (this, result, TokenType.ATOM, loc) :
                        new Token(this, result, TokenType.FORMULA, loc);
            }

            case PARENTHESIS_CLOSE: throw new SyntaxException(loc, next, baseString);

            case ATOM: {
                errorNext(loc+1, TokenType.UNARY_CONNECTIVE, TokenType.PARENTHESIS_OPEN);
                loc -= next.length()-1; // Reset loc to the start of atom string
                break;
            }
            case UNARY_CONNECTIVE:
            case BINARY_CONNECTIVE: {
                // Cases like "A&" or "!"
                errorNoNext(loc+1);
                errorNext(loc+1, TokenType.BINARY_CONNECTIVE, TokenType.PARENTHESIS_CLOSE);
                break;
            }
            case UNKNOWN:
                throw new SyntaxException(loc, "Unrecognized character \"" + next + "\"", baseString);
        }

        next.setLocation(loc);
        return next;
    }

    /**
     * @return 1. String of alphabetic character(s)
     *         2. Char of parenthesis
     *         3. Char of connectives
     *         4. Char of unrecognized characters (Numbers, Unicode)
     *         5. Empty token if the index is at the end
     */
    private Token next() {
        String ch = nextChar();
        // FEATURE: implement TokenType.END (may not be necessary)
        /*
        if (ch == null) { // !hasNext()
            return new Token(this, "", TokenType.END);
        }
        */

        TokenType type;
        if (parser.isBinaryConnective(ch)) type = TokenType.BINARY_CONNECTIVE;
        else if (parser.isUnaryConnective(ch)) type = TokenType.UNARY_CONNECTIVE;
        else if (LojicParser.isOpenParenthesis(ch)) {
            type = TokenType.PARENTHESIS_OPEN;
        }
        else if (LojicParser.isCloseParenthesis(ch)) {
            type = TokenType.PARENTHESIS_CLOSE;
        }
        else if (LojicParser.isAtomic(ch)) {
            type = TokenType.ATOM;
            StringBuilder cache = new StringBuilder(ch);
            while (hasNext()) {
                ch = peekChar();
                if (LojicParser.isAtomic(ch)) {
                    cache.append(ch);
                    index++;
                } else {
                    break;
                }
            }
            ch = cache.toString();
        }
        else type = TokenType.UNKNOWN;

        return new Token(this, ch, type);
    }

    /**
     * Read the next char
     *
     * @return The char, or null if there are no chars left
     */
    private String nextChar() {
        String next = peekChar();
        if(next != null) index++; // reset index
        return next;
    }

    private String peekChar() {
        if (hasNext()) {
            return string.substring(index+1, index+2);
        } else {
            return null;
        }
    }

    // Check if the next token is of certain TokenType. If so, throw SyntaxException
    // FEATURE: No symbols stripping - Change for loop
    private void errorNext(int location, TokenType... types) throws SyntaxException {
        String next = peekChar();
        if (next == null) return;

        for (TokenType tp : types) {
            // See the end of the doc for enhanced switch replacement (temporary disabled to support earlier version)
            boolean error = false;
            switch (tp) {
                case UNARY_CONNECTIVE: {
                    error = parser.isUnaryConnective(next);
                    break;
                }
                case BINARY_CONNECTIVE: {
                    error = parser.isBinaryConnective(next);
                    break;
                }

                case PARENTHESIS_OPEN: {
                    error = LojicParser.isOpenParenthesis(next);
                    break;
                }
                case PARENTHESIS_CLOSE: {
                    error = LojicParser.isCloseParenthesis(next);
                    break;
                }

                case ATOM: {
                    error = LojicParser.isAtomic(next);

                }
            }

            if (error) throw new SyntaxException(location, (CharSequence) next, baseString);
        }
    }

    // Check if there is a next token. If not, throw SyntaxException
    private void errorNoNext(int location) throws SyntaxException {
        if (!hasNext()) throw new SyntaxException(location, "Missing atom or formula at the end of the expression", baseString);
    }

    private boolean hasNext() {
        return string.length() > index+1;
    }

    /*
    boolean error = switch (tp) {
        case UNARY_CONNECTIVE -> parser.isUnaryConnective(next);
        case BINARY_CONNECTIVE -> parser.isBinaryConnective(next);

        case PARENTHESIS_OPEN -> LojicParser.isOpenParenthesis(next);
        case PARENTHESIS_CLOSE -> LojicParser.isCloseParenthesis(next);

        case ATOM -> LojicParser.isAtomic(next);

        default -> false;
    }
    */


}
