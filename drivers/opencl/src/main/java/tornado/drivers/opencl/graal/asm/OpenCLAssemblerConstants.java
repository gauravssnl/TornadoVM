package tornado.drivers.opencl.graal.asm;

public class OpenCLAssemblerConstants {

    public static final String KERNEL_MODIFIER = "__kernel";
    public static final String EOL = "\n";
    public static final String GLOBAL_MEM_MODIFIER = "__global";
    public static final String SHARED_MEM_MODIFIER = "__shared";
    public static final String LOCAL_MEM_MODIFIER = "__local";
    public static final String PRIVATE_MEM_MODIFIER = "__private";
    public static final String CONSTANT_MEM_MODIFIER = "__constant";

    public static final String GLOBAL_REGION_NAME = "_global_region";
    public static final String LOCAL_REGION_NAME = "_local_region";
    public static final String PRIVATE_REGION_NAME = "_private_region";
    public static final String CONSTANT_REGION_NAME = "_constant_region";

    public static final String HEAP_REF_NAME = "_heap_base";
    public static final String STACK_REF_NAME = "_stack_base";
    public static final String FRAME_REF_NAME = "_frame";

    public static final String STMT_DELIMITER = ";";
    public static final String EXPR_DELIMITER = ",";
    public static final String COLON = ":";
    public static final String FOR_LOOP = "for";
    public static final String IF_STMT = "if";
    public static final String SWITCH = "switch";
    public static final String CASE = "case";
    public static final String DEFAULT_CASE = "default";
    public static final String BREAK = "break";
    public static final String TAB = "  ";
    public static final String ASSIGN = " = ";
    public static final String CURLY_BRACKET_OPEN = "{";
    public static final String CURLY_BRACKET_CLOSE = "}";
     public static final String ADDRESS_OF = "&";

    public static final String BRACKET_OPEN = "(";
    public static final String BRACKET_CLOSE = ")";

    public static final String SQUARE_BRACKETS_OPEN = "[";
    public static final String SQUARE_BRACKETS_CLOSE = "]";

    public static final String ADD = "+";
    public static final String SUB = "-";
    public static final String MULT = "*";
    public static final String DIV = "/";
    public static final String MOD = "%";

    public static final String COMPARE = "==";
    public static final String LT = "<";
    public static final String LE = "=<";
    public static final String GT = ">";
    public static final String GE = ">=";

    public static final String NOT = "!";

    public static final String OR = "|";
    public static final String AND = "&";
    public static final String XOR = "^";

    public static final String SHL = "<<";
    public static final String SHR = ">>";
    public static final String ABS = "abs";
    public static final String SQRT = "sqrt";
    public static final String POP_COUNT = "popcount";

    public static final String FMIN = "fmin";
    public static final String FMAX = "fmax";
    public static final String MIN = "min";
    public static final String MAX = "max";
    public static final String ELSE = "else";

    public static final int STACK_BASE_OFFSET = 6;

}
