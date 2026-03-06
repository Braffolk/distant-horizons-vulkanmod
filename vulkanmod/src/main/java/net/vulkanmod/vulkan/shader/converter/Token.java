package net.vulkanmod.vulkan.shader.converter;

public class Token {

    public enum TokenType {
        PREPROCESSOR,
        KEYWORD,
        IDENTIFIER,
        LITERAL,
        OPERATOR,
        PUNCTUATION,
        SPACING,
        COMMENT,

        // Symbols
        LEFT_BRACE,   // {
        RIGHT_BRACE,  // }
        LEFT_PARENTHESIS,   // (
        RIGHT_PARENTHESIS,  // )
        COLON,    // :
        SEMICOLON,    // ;
        DOT,    // .
        COMMA,    // ,

        // Data Types
        TYPE,

        // GLSL
        LAYOUT,

        EOF
    }

    public final TokenType type;
    public String value;

    public Token(TokenType type, String value) {
        this.type = type;
        this.value = value;
    }

    @Override
    public String toString() {
        return "Token{" +
               "type=" + type +
               ", value='" + value + '\'' +
               '}';
    }
}
