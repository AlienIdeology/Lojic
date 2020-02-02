package lojic.parser;

import lojic.parser.token.Token;
import lojic.parser.token.TokenList;

/**
 * @author AlienIdeology
 *
 * A lexer of logical expressions
 * Tokenizes a string in terms of {@code alphabetic-chars|parenthesized-formulas|connective-char|unrecognized-char}
 * {@code alphabetic-chars} and {@code parenthesized-formulas} are grouped into char sequences
 */
public class LojicLexer {

    private final String baseString;
    private String string;
    private int index;

    public LojicLexer(String baseString, String string) {
        this.baseString = baseString;
        this.string = string;
        this.index = -1;
    }

    public void setIndex(int in) {
        index = in;
    }

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
     * @param location The location of the formula in the base formula in which this lexer starts
     * @return a list of tokens
     */
    public TokenList lex(int location) {
        TokenList tokens = new TokenList();

        while (true) {
            while (hasNext()) {
                tokens.add(handleNext( location + index + 1)); // reader index starts at -1, so offset it by 1
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
            if (tokens.size()==1){
                updateString(tokens.get(0).toString());
                // "A" => "A"
                // "(A)" => "A"
                if (tokens.get(0).isType(Token.Type.ATOM)) break;

                    // "(((A&B)))" => ""A&B"
                else {
                    tokens.clear();
                    setIndex(-1);
                }
            }
            else break;
        }

        return tokens;
    }

    // Grouping parenthesis and handling errors

    /*
     * Handle all syntax errors
     * This method group parenthesized strings into formulas or atoms, and un-parenthesize it.
     */

    /**
     * This method group parenthesized strings into formulas or atoms, and un-parenthesize it.
     *
     * @param location The location of the formula in the base formula in which this lexer starts
     * @return The next token
     * @throws SyntaxException for various syntax errors
     */
    // Redundant method, can collapse this into next()
    private Token handleNext(int location) {
        Token next = next();
        Token.Type type = next.getType();

        // loc = location + lexer index
        // errorNoNext requires -1; throw new Exception requires -1 or -2
        int loc = location + next.length();

        switch (type) {
            case PARENTHESIS_OPEN: {
                int count = 1;
                StringBuilder cache = new StringBuilder(next.toString());
                errorNext(loc, Token.Type.BINARY_CONNECTIVE);

                while (count !=0) {
                    // Cases like "(" or "((" or "((P->Q)"
                    if (!hasNext()) {
                        // "(", "(("
                        if(LojicUtil.isOpenParenthesis(cache.substring(cache.length()-1))) {
                            errorNoNext(loc-1);
                            // "((P->Q)"
                        } else {
                            throw new SyntaxException(loc-1, "Missing closing parenthesis",
                                    LojicUtil.generateIndicator(baseString, loc-1));
                        }
                    }

                    next = next();
                    loc += next.length();

                    if (next.isType(Token.Type.PARENTHESIS_OPEN)) {
                        count++;
                    } else if (next.isType(Token.Type.PARENTHESIS_CLOSE)) {
                        count--;
                    }
                    cache.append(next.toString());
                }

                String result = cache.toString();
                // Remove parenthesis in front and at the end
                result = result.substring(1, result.length()-1);

                // Empty Formula ()
                if(result.isEmpty()) throw new SyntaxException(loc-2, "Empty formula",
                        LojicUtil.generateIndicator(baseString, loc-2));

                // Return formula of atom
                return LojicUtil.isAtomic(result) ? new Token (this, result, Token.Type.ATOM, location) :
                        new Token(this, result, Token.Type.FORMULA, location);
            }

            case PARENTHESIS_CLOSE: throw new SyntaxException(loc-1, next,
                    LojicUtil.generateIndicator(toString(), loc-1));

            case ATOM: {
                errorNext(loc, Token.Type.UNARY_CONNECTIVE, Token.Type.PARENTHESIS_OPEN);
                break;
            }
            case UNARY_CONNECTIVE:
            case BINARY_CONNECTIVE: {
                // Cases like "A&" or "!"
                errorNoNext(loc-1);
                errorNext(loc, Token.Type.BINARY_CONNECTIVE, Token.Type.PARENTHESIS_CLOSE);
                break;
            }
            case UNKNOWN:
                throw new SyntaxException(loc, "Unrecognized character " + next,
                        LojicUtil.generateIndicator(baseString, loc));
        }

        next.setLocation(location);
        return next;
    }

    /**
     * @return 1. String of alphabetic character(s)
     *         2. Char of parenthesis
     *         3. Char of connectives
     *         4. Char of unrecognized characters (Numbers, Unicodes)
     */
    private Token next() {
        index++;
        String ch = string.substring(index, index+1);
        Token.Type type;

        if (LojicUtil.isBinaryConnective(ch)) type = Token.Type.BINARY_CONNECTIVE;
        else if (LojicUtil.isUnaryConnective(ch)) type = Token.Type.UNARY_CONNECTIVE;
        else if (LojicUtil.isOpenParenthesis(ch)) {
            ch = Token.Type.PARENTHESIS_OPEN.OFFICIAL_SYMBOL;
            type = Token.Type.PARENTHESIS_OPEN;
        }
        else if (LojicUtil.isCloseParenthesis(ch)) {
            ch = Token.Type.PARENTHESIS_CLOSE.OFFICIAL_SYMBOL;
            type = Token.Type.PARENTHESIS_CLOSE;
        }
        else if (LojicUtil.isAtomic(ch)) {
            type = Token.Type.ATOM;
            StringBuilder cache = new StringBuilder(ch);
            while (hasNext()) {
                ch = peekChar();
                if (LojicUtil.isAtomic(ch)) {
                    cache.append(ch);
                    index++;
                } else {
                    break;
                }
            }
            ch = cache.toString();
        }
        else type = Token.Type.UNKNOWN;

        return new Token(this, ch, type);
    }

    /**
     * @return The next Token, or null if there is no next Token
     */
    private Token peek() {
        Token token = null;
        if (hasNext()) {
            int temp = index;
            token = next();
            setIndex(temp);
        }
        return token;
    }

    // Check if the next token is of certain TokenType. If so, throw SyntaxException
    private void errorNext(int location, Token.Type... types) {
        if (hasNext()) {
            Token token = peek();
            for (Token.Type tp : types) {
                if (token.isType(tp)) throw new SyntaxException(location,
                        (CharSequence) token.toString(),
                        LojicUtil.generateIndicator(baseString, location));
            }
        }
    }

    // Check if there is a next token. If not, throw SyntaxException
    private void errorNoNext(int location) {
        if (!hasNext()) throw new SyntaxException(location,
                "Missing atom or formula at the end of the expression",
                LojicUtil.generateIndicator(baseString, location));
    }

    private String peekChar() {
        return string.substring(index+1, index+2);
    }

    private boolean hasNext() {
        return string.length() > index+1;
    }

}