package util.code;

import common.GenCodeRequest;
import common.Settings;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static util.CompatibilityUtil.putToList;
import static util.code.ObjectCodeHelper.*;

public class ObjectCodeGenerator {
    private class ObjectCode {
        int level;
        int constructorLevel;
        String variableType;
        String referenceName;
        Object object;

        String keyType;
        String valueType;

        String assignmentCode;

        ObjectCode(int level, String referenceName, Object object, String variableType) {
            this.level = level;
            this.referenceName = referenceName;
            this.object = object;
            this.constructorLevel = level;
            this.variableType = variableType;
        }

        public void generateAssignmentCode() {
            Class<?> clazz = object.getClass();

            if (clazz.isArray()) {
                assignmentCode = getArrayCode(object, level, referenceName);
            } else if (object instanceof Collection) {
                assignmentCode = getCollectionCode((Collection<?>) object, level, referenceName);
            } else if (object instanceof Map) {
                assignmentCode = getMapCode((Map<?, ?>) object, level, referenceName);
            } else {
                assignmentCode = getPojoCode(object, level, referenceName);
            }
        }

        String getConstructCode() {
            Class<?> clazz = object.getClass();
            String simpleName = getSimpleName(clazz.getName());
            String className0;
            if (variableType == null) {
                className0 = simpleName;
            } else {
                className0 = settings.isUseBaseClasses() ? variableType : simpleName;
            }

            if (className0.isEmpty() && variableType != null) {
                className0 = variableType;
            }

            if (className0.isEmpty()) {
                // it is anonymous, find base class
                className0 = getSimpleNameFromSuperClass(clazz);
            }

            String constructorCall;
            if (clazz.isArray()) {
                int length = Array.getLength(object);
                constructorCall = simpleName.replace("[]", "[" + length + "]");
            } else {
                constructorCall = simpleName + generateCtorGenerics() + "()";
                if (simpleName.isEmpty()) {
                    simpleName = getSimpleNameFromSuperClass(clazz);
                    constructorCall = simpleName + generateCtorGenerics() + "() {/* anonymous class */}";
                }
            }

            return className0 + generateVarGenerics() + " " + referenceName + " = new " + constructorCall + ";\n";
        }

        private String generateVarGenerics() {
            if (keyType != null && valueType != null) {
                return String.format("<%s, %s>", keyType, valueType);
            } else if (keyType != null) {
                return String.format("<%s>", keyType);
            }
            return "";
        }

        private String generateCtorGenerics() {
            if (keyType != null || valueType != null) {
                return "<>";
            }
            return "";
        }

        private String getMapCode(Map<?, ?> object, int level, String referenceName) {
            StringBuilder str = new StringBuilder();
            Class<?> keyClass = null;
            Class<?> valueClass = null;
            boolean useGenerics = isUseGenerics(settings, object.getClass());
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                if (useGenerics) {
                    keyClass = narrow(keyClass, key);
                    valueClass = narrow(valueClass, value);
                }
                String keyStr = createObjectCode(key, level + 1, null, null);
                String valStr = createObjectCode(value, level + 1, null, null);
                str.append(referenceName).append(".put(").append(keyStr).append(", ").append(valStr).append(");\n");
            }
            if (keyClass != null) {
                keyType = keyClass.getSimpleName();
            }
            if (valueClass != null) {
                valueType = valueClass.getSimpleName();
            }
            return str.toString();
        }

        private String getCollectionCode(Collection<?> object, int level, String referenceName) {
            StringBuilder str = new StringBuilder();
            Class<?> keyClass = null;
            boolean useGenerics = isUseGenerics(settings, object.getClass());
            for (Object ele : object) {
                if (useGenerics) {
                    keyClass = narrow(keyClass, ele);
                }
                String eleVal = createObjectCode(ele, level + 1, null, null);
                str.append(referenceName).append(".add(").append(eleVal).append(");\n");
            }
            if (keyClass != null) {
                keyType = keyClass.getSimpleName();
            }
            return str.toString();
        }

        private String getArrayCode(Object object, int level, String referenceName) {
            StringBuilder str = new StringBuilder();
            int length = Array.getLength(object);
            for (int i = 0; i < length; i++) {
                String eleVal = createObjectCode(Array.get(object, i), level + 1, null, null);
                str.append(referenceName).append("[").append(i).append("] = ").append(eleVal).append(";\n");
            }
            return str.toString();
        }
    }

    private final Object rootObj;
    private final Settings settings;

    private final String variableName;
    private final String variableType;

    private final UniqueNameGenerator uniqueNameGenerator = new UniqueNameGenerator();
    private final Map<Object, ObjectCode> existingObjectCode = new IdentityHashMap<>();

    public ObjectCodeGenerator(Object rootObj, GenCodeRequest genCodeRequest) {
        this.rootObj = rootObj;
        this.settings = genCodeRequest.getSettings();
        this.variableName = genCodeRequest.getVariableName();
        String variableType = genCodeRequest.getVariableType();
        this.variableType = variableType != null ? getSimpleName(variableType) : null;
    }

    public String genCode() {
        String root = createObjectCode(this.rootObj, 0, this.variableType, this.variableName);

        List<ObjectCode> outputCodes = new ArrayList<>(existingObjectCode.values());
        Collections.sort(outputCodes, new Comparator<ObjectCode>() {
            @Override
            public int compare(ObjectCode a, ObjectCode b) {
                if (b.constructorLevel == a.constructorLevel) {
                    if (a.level == b.level) {
                        return a.referenceName.compareTo(b.referenceName);
                    } else {
                        return a.level - b.level;
                    }
                } else {
                    return b.constructorLevel - a.constructorLevel;
                }
            }
        });

        StringBuilder ret = new StringBuilder();
        int curLevel = Integer.MAX_VALUE;
        Map<Integer, List<ObjectCode>> remainingFieldsCode = new HashMap<>();
        boolean assignmentJustAdded = false;
        for (ObjectCode objectCode : outputCodes) {
            //meaning highest constructor level end, dump all remained field setters at that level
            if (curLevel > objectCode.constructorLevel) {
                List<ObjectCode> remained = remainingFieldsCode.get(curLevel);
                if (remained != null) {
                    for (ObjectCode fieldCode : remained) {
                        // ret.append("// deep forward " + fieldCode.referenceName);
                        ret.append("\n");
                        ret.append(fieldCode.assignmentCode);
                        assignmentJustAdded = true;
                    }
                    remainingFieldsCode.remove(curLevel);
                }
                curLevel = objectCode.constructorLevel;
            }

            // add empty line before new block with assignments or after real assignments
            if (ret.length() != 0 && !objectCode.assignmentCode.isEmpty() || assignmentJustAdded) {
                ret.append("\n");
            }

            ret.append(objectCode.getConstructCode());
            assignmentJustAdded = false;

            if (!objectCode.assignmentCode.isEmpty()) { // no need to handle empty assignments
                if (objectCode.constructorLevel == objectCode.level) {
                    ret.append(objectCode.assignmentCode);
                    assignmentJustAdded = true;
                } else {
                    putToList(remainingFieldsCode, objectCode.level, objectCode);
                }
            }
        }

        for (List<ObjectCode> remains : remainingFieldsCode.values()) {
            for (ObjectCode r : remains) {
                ret.append("\n");
                ret.append(r.assignmentCode);
            }
        }

        if (ret.length() == 0) {
            String simpleName = rootObj.getClass().getSimpleName();
            ret.append(String.format("%s %s = %s;", simpleName, firstLower(simpleName), root));
        }

        return ret.toString();
    }

    private String createObjectCode(Object object, int level, String variableType, String variableName) {
        if (object == null || level > this.settings.getMaxLevel()) {
            return "null";
        } else if (isWrapperType(object.getClass())) {
            if (object instanceof Float) {
                return object + "f";
            } else if (object instanceof Long) {
                return object + "L";
            } else if (object instanceof Character) {
                return "'" + object + "'";
            } else {
                return object.toString();
            }
        } else if (object instanceof String) {
            return "\"" + escape((String) object) + "\"";
        } else if (object instanceof Enum) {
            return object.getClass().getSimpleName() + "." + object;
        } else if (object instanceof Date) {
            return "new " + object.getClass().getSimpleName() + "(" + ((Date) object).getTime() + ")";
        } else if (object instanceof BigDecimal) {
            return "new " + object.getClass().getSimpleName() + "(" + object + ")";
        } else if (object instanceof BigInteger) {
            return "new " + object.getClass().getSimpleName() + "(\"" + object + "\")";
        } else {
            ObjectCode existed = existingObjectCode.get(object);
            if (existed != null) {
                if (existed.constructorLevel < level) {
                    existed.constructorLevel = level;
                }
                return existed.referenceName;
            } else {
                String referenceName = variableName != null
                        ? uniqueNameGenerator.createUniqueName(variableName)
                        : uniqueNameGenerator.genReferenceName(object.getClass());
                ObjectCode objectCode = new ObjectCode(level, referenceName, object, variableType);
                existingObjectCode.put(object, objectCode);
                objectCode.generateAssignmentCode();
                return referenceName;
            }
        }
    }

    private String getPojoCode(Object object, int level, String referenceName) {
        Class<?> clz = object.getClass();
        StringBuilder str = new StringBuilder();
        List<Field> fields = getAllFields(clz);
        for (Field field : fields) {
            try {
                field.setAccessible(true);

                int modifiers = field.getModifiers();
                if (Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) {
                    continue; // ignore final and static fields
                }

                Class<?> type = field.getType();
                String fieldName = field.getName();

                Method setter = null;
                try {
                    String setterName = firstUpper(fieldName);
                    setter = clz.getMethod("set" + setterName, type);
                } catch (NoSuchMethodException ignored) {
                }

                if (this.settings.isSupportUnderscores() && setter == null && field.getName().startsWith("_")) {
                    try {
                        fieldName = fieldName.substring(1);
                        String setterName = firstUpper(fieldName);
                        setter = clz.getMethod("set" + setterName, type);
                    } catch (NoSuchMethodException ignored) {
                    }
                }

                if (setter == null && Modifier.isPrivate(modifiers)) {
                    continue;
                }

                Object value = field.get(object);
                if (value == null && this.settings.isSkipNulls()) {
                    continue;
                }

                if (this.settings.isSkipDefaults() && type.isPrimitive()) {
                    if (Objects.equals(value, false)) {
                        continue;
                    }
                    if (Objects.equals(value, 0)) {
                        continue;
                    }
                    if (Objects.equals(value, 0L)) {
                        continue;
                    }
                    if (Objects.equals(value, 0D)) {
                        continue;
                    }
                    if (Objects.equals(value, 0F)) {
                        continue;
                    }
                }

                str.append(referenceName).append(".");
                if (setter != null) {
                    Class<?>[] parameterTypes = setter.getParameterTypes();
                    String fieldClassName = parameterTypes.length == 1 ? parameterTypes[0].getSimpleName() : null;
                    String fieldVal = createObjectCode(value, level + 1, fieldClassName, fieldName);
                    str.append(setter.getName()).append("(").append(fieldVal).append(")");
                } else {
                    String fieldClassName = field.getType().getSimpleName();
                    String fieldVal = createObjectCode(value, level + 1, fieldClassName, fieldName);
                    str.append(field.getName()).append(" = ").append(fieldVal);
                }
                str.append(";\n");
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot access field", e);
            }
        }
        return str.toString();
    }
}
