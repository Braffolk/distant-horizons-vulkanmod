package net.vulkanmod.vulkan.shader.converter;

import java.util.ArrayList;
import java.util.List;

public class Lexer {
    private final String input;
    private int currentPosition;
    private char currentChar;

    private State state;

    public Lexer(String input) {
        this.input = input;
        this.currentPosition = 0;
        this.currentChar = !input.isEmpty() ? input.charAt(0) : '\0';
    }

    private void advance() {
        advance(1);
    }

    private void advance(int i) {
        for (int j = 0; j < i; j++) {
            currentPosition++;
            if (currentPosition >= input.length()) {
                currentChar = '\0';
                break;
            } else {
                currentChar = input.charAt(currentPosition);
            }
        }
    }

    private char peek() {
        int peekPosition = currentPosition + 1;
        if (peekPosition < input.length()) {
            return input.charAt(peekPosition);
        }
        return '\0';
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();

        while (currentPosition < input.length()) {
            char currentChar = input.charAt(currentPosition);

            Token token = nextToken();
            if (token != null) {
                tokens.add(token);
            } else {
                throw new RuntimeException("Unknown character: " + currentChar);
            }
        }

        tokens.add(new Token(Token.TokenType.EOF, null));

        return tokens;
    }

    public Token nextToken() {
        if (!checkEOF()) {
            return new Token(Token.TokenType.EOF, null);
        }

        // Comment
        if (currentChar == '/') {
            if (peek() == '/') {
                advance(2);
                return this.comment();
            }
        }

        // Handle multi-character operators
        switch (currentChar) {
            case '=':
                if (peek() == '=') {
                    advance(2);
                    return new Token(Token.TokenType.OPERATOR, "==");
                }
                break;
            case '!':
                if (peek() == '=') {
                    advance(2);
                    return new Token(Token.TokenType.OPERATOR, "!=");
                }
                break;
            case '<':
                switch (peek()) {
                    case '=' -> {
                        advance(2);
                        return new Token(Token.TokenType.OPERATOR, "<=");
                    }
                    case '<' -> {
                        advance(2);
                        return new Token(Token.TokenType.OPERATOR, "<<");
                    }
                }
                break;
            case '>':
                switch (peek()) {
                    case '=' -> {
                        advance(2);
                        return new Token(Token.TokenType.OPERATOR, ">=");
                    }
                    case '>' -> {
                        advance(2);
                        return new Token(Token.TokenType.OPERATOR, ">>");
                    }
                }
                break;
        }

        Token token = switch (currentChar) {
            case '{' -> new Token(Token.TokenType.LEFT_BRACE, "{");
            case '}' -> new Token(Token.TokenType.RIGHT_BRACE, "}");
            case '(' -> new Token(Token.TokenType.LEFT_PARENTHESIS, "(");
            case ')' -> new Token(Token.TokenType.RIGHT_PARENTHESIS, ")");
            case ':' -> new Token(Token.TokenType.COLON, ":");
            case ';' -> new Token(Token.TokenType.SEMICOLON, ";");
            case '.' -> new Token(Token.TokenType.DOT, ".");
            case ',' -> new Token(Token.TokenType.COMMA, ",");

            case '=' -> new Token(Token.TokenType.OPERATOR, "=");
            case '+' -> new Token(Token.TokenType.OPERATOR, "+");
            case '-' -> new Token(Token.TokenType.OPERATOR, "-");
            case '*' -> new Token(Token.TokenType.OPERATOR, "*");
            case '/' -> new Token(Token.TokenType.OPERATOR, "/");
            case '%' -> new Token(Token.TokenType.OPERATOR, "%");
            case '<' -> new Token(Token.TokenType.OPERATOR, "<");
            case '>' -> new Token(Token.TokenType.OPERATOR, ">");
            case '!' -> new Token(Token.TokenType.OPERATOR, "!");
            case '&' -> new Token(Token.TokenType.OPERATOR, "&");
            case '|' -> new Token(Token.TokenType.OPERATOR, "|");
            case '^' -> new Token(Token.TokenType.OPERATOR, "^");
            case '?' -> new Token(Token.TokenType.OPERATOR, "?");
            case '[' -> new Token(Token.TokenType.OPERATOR, "[");
            case ']' -> new Token(Token.TokenType.OPERATOR, "]");

            case '#' -> {
                StringBuilder sb = new StringBuilder();

                while (checkEOF() && currentChar != '\n') {
                    sb.append(currentChar);
                    advance();
                }
                sb.append('\n');

                String value = sb.toString();
                yield new Token(Token.TokenType.PREPROCESSOR, value);
            }

            case '\"' -> string();

            default -> null;
        };

        if (token == null) {
            if (Character.isLetter(currentChar)) {
                return identifier();
            }

            if (Character.isDigit(currentChar)) {
                return literal();
            }

            if (Character.isWhitespace(currentChar)) {
                return spacing();
            }
        }

        if (token == null) {
            throw new IllegalStateException("Unrecognized char: " + currentChar);
        }

        advance();

        return token;
    }

    private Token comment() {
        StringBuilder sb = new StringBuilder();
        sb.append("//");
        while (checkEOF() && currentChar != '\n') {
            sb.append(currentChar);
            advance();
        }
        sb.append(currentChar);
        advance();

        String value = sb.toString();
        return new Token(Token.TokenType.COMMENT, value);
    }

    private Token identifier() {
        StringBuilder sb = new StringBuilder();
        while (checkEOF() && Character.isJavaIdentifierPart(currentChar)) {
            sb.append(currentChar);
            advance();
        }
        String value = sb.toString();
        return new Token(Token.TokenType.IDENTIFIER, value);
    }

    private Token literal() {
        StringBuilder sb = new StringBuilder();
        while (Character.isDigit(currentChar)) {
            sb.append(currentChar);
            advance();
        }

        if (currentChar == '.') {
            sb.append(currentChar);
            advance();
        }

        while (Character.isDigit(currentChar)) {
            sb.append(currentChar);
            advance();
        }

        String value = sb.toString();
        return new Token(Token.TokenType.LITERAL, value);
    }

    private Token string() {
        StringBuilder sb = new StringBuilder();
        while (checkEOF() && currentChar != '\"') {
            sb.append(currentChar);
            advance();
        }
        sb.append(currentChar);
        advance();

        String value = sb.toString();
        return new Token(Token.TokenType.COMMENT, value);
    }

    private Token spacing() {
        StringBuilder sb = new StringBuilder();
        while (currentChar != '\0' && Character.isWhitespace(currentChar)) {
            sb.append(currentChar);
            advance();
        }
        String value = sb.toString();
        return new Token(Token.TokenType.SPACING, value);
    }

    private boolean checkEOF() {
        return currentChar != '\0';
    }

    enum State {
        UNIFORM_BLOCK,
        CODE,
        DEFAULT
    }
}
