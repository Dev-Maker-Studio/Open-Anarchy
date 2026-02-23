package com.rs.cache.loaders;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub QCMesDefinitions. Returns placeholder values until wired to the modern cache system.
 */
public class QCMesDefinitions {

    private static final ConcurrentHashMap<Integer, QCMesDefinitions> CACHE = new ConcurrentHashMap<>();

    public int id;
    public String[] message;
    public int[] responses;
    public QCValueType[] types;
    public int[][] configs;
    public boolean searchable = true;

    public static final QCMesDefinitions getDefs(int id) {
        QCMesDefinitions defs = CACHE.get(id);
        if (defs != null)
            return defs;
        defs = new QCMesDefinitions();
        defs.id = id;
        defs.message = new String[] { "" };
        // TODO: Load from modern cache
        CACHE.put(id, defs);
        return defs;
    }

    public String getFilledQuickchat(com.rs.lib.io.InputStream stream) {
        StringBuilder builder = new StringBuilder(80);
        if (this.types != null) {
            for (int i = 0; i < this.types.length; i++) {
                builder.append(this.message[i]);
                builder.append(getFilledValue(this.types[i], this.configs[i], stream.readSized(this.types[i].serverSize)));
            }
        }
        builder.append(this.message[this.message.length - 1]);
        return builder.toString();
    }

    public String getFilledValue(QCValueType type, int[] configs, long data) {
        if (type == QCValueType.LISTDIALOG) {
            EnumDefinitions enumDef = EnumDefinitions.getEnum(configs[0]);
            return enumDef.getStringValue((int) data);
        } else if (type != QCValueType.OBJDIALOG && type != QCValueType.OBJTRADEDIALOG) {
            return type != QCValueType.ENUM_STRING && type != QCValueType.ENUM_STRING_CLAN && type != QCValueType.ENUM_STRING_STATBASE ? null : EnumDefinitions.getEnum(configs[0]).getStringValue((int) data);
        } else {
            return ItemDefinitions.getDefs((int) data).name;
        }
    }

    public enum QCValueType {
        LISTDIALOG(0, 2, 2, 1),
        OBJDIALOG(1, 2, 2, 0),
        COUNTDIALOG(2, 4, 4, 0),
        STAT_BASE(4, 1, 1, 1),
        ENUM_STRING(6, 0, 4, 2),
        ENUM_STRING_CLAN(7, 0, 1, 1),
        TOSTRING_VARP(8, 0, 4, 1),
        TOSTRING_VARBIT(9, 0, 4, 1),
        OBJTRADEDIALOG(10, 2, 2, 0),
        ENUM_STRING_STATBASE(11, 0, 1, 2),
        ACC_GETCOUNT_WORLD(12, 0, 1, 0),
        ACC_GETMEANCOMBATLEVEL(13, 0, 1, 0),
        TOSTRING_SHARED(14, 0, 4, 1),
        ACTIVECOMBATLEVEL(15, 0, 1, 0);

        private static HashMap<Integer, QCValueType> MAP = new HashMap<>();

        static {
            for (QCValueType type : QCValueType.values()) {
                MAP.put(type.id, type);
            }
        }

        public int id, clientSize, serverSize, paramCount;

        private QCValueType(int id, int clientSize, int serverSize, int paramCount) {
            this.id = id;
            this.clientSize = clientSize;
            this.serverSize = serverSize;
            this.paramCount = paramCount;
        }

        public static QCValueType get(int id) {
            return MAP.get(id);
        }
    }
}
